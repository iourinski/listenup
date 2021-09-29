package com.listenup.challenge.actors

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.listenup.challenge.dataschema.{FriendsStat, FriendsStatBatch, PlayStat, PlayStatBatch, User}

import java.time.Instant
import scala.collection.mutable
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.OverflowStrategy
import akka.stream.javadsl.Sink
import akka.stream.scaladsl.{Keep, Source, SourceQueueWithComplete}
import akka.util.Timeout
import com.listenup.challenge.configs
import com.listenup.challenge.configs.{FriendsConfig, ListenUpConfig, PlaysConfig}
import com.listenup.challenge.routes.{BatchLookupResult, NotFound, SingleUserLookupResult, UserMessages}

import java.util.UUID
import scala.concurrent.Promise
import scala.util.{Failure, Success}

sealed trait LookupMessage
/** Query from server (routes) */
final case class SingleUserLookup(username: String, respondTo: ActorRef[UserMessages]) extends LookupMessage
final case class BatchLookup(respondTo: ActorRef[UserMessages]) extends LookupMessage
/** Messages received from Play actor */
final case class SingleUserPlayResult(userName: String, stats: PlayStat) extends LookupMessage
final case class BatchUserPlayResult(stats: PlayStatBatch) extends LookupMessage
/** Messages from Friends actor */
final case class SingleUserFriendsResult(userName: String, stats: FriendsStat) extends LookupMessage
final case class BatchUserFriendsResult(stats: FriendsStatBatch) extends LookupMessage
/** Self directed message for refresing mined data */
final case class UpdateReminder() extends LookupMessage

/**
 * "Cache" actor, capable of "lazily" updating stored data (only if a request comes after the lookup's table age is above
 * update frequency, if no requests are send, the data is not updated)
 * @param system Akka actor system
 */
class LookupActor () (implicit  val system: ActorSystem[_]){
  private implicit val timeout: Timeout = Timeout.create(new configs.ListenUpConfig().getFullConfig.getDuration("ask-timeout"))
  /** Lookup with aggregated data from Plays and Friends services (updated periodically) */
  private val lookup = new mutable.HashMap[String, User]()
  private val prefix = "/users"
  /** Properties from application.conf file */
  private val conf = new ListenUpConfig()
  /** Connection poll size */
  private val bufferSize = conf.getFullConfig.getInt("buffer-size")
  /** Future timeout */
  private val timeoutSec = conf.getFullConfig.getInt("timeout-sec")
  /** Max age of lookup map in seconds */
  private val updateFreq = conf.getFullConfig.getInt("update-frequency")
  /** Since some data may be lost (Friends/Play service down, dropped connection)
   * what percentage  of values do we want to guarantee to be accurate
   */
  private val consistency = conf.getFullConfig.getDouble("consistency")
  /** Request timeout  */
  private val askTimeout = conf.getFullConfig.getDuration("ask-timeout")
  /** Creating connection polls for Play service */
  val playConfig = new  PlaysConfig()
  private val playRequestQueue = createHttpRequestQueue(playConfig.getUrl, playConfig.getPort)
  /** Same for Friends service */
  val friendsConfig = new FriendsConfig()
  private val friendRequestQueue = createHttpRequestQueue(friendsConfig.getUrl, friendsConfig.getPort)

  /** Bookkeping variables (needed to keep track of data reading, accessing and age) */
  private var lastFullLookup: Instant = Instant.now()
  /** A flag showing if lookup table is updated and consistent */
  private var okToReturn = false
  /** Used to ensure that update is consistent */
  private var playsRequest =  -1
  private var friendsRequest =  -1
  private var updateStarted = false
  private var playsCount = 0
  private var friendsCount = 0
  /** If a request comes when no consistent data is available it is put to queue to wait until the data is
   * available (or it times out)
   */
  private val queue: mutable.Queue[(LookupMessage, Instant)] = new mutable.Queue()

  def create(): Behavior[LookupMessage] = {
    Behaviors.setup {context =>
      Behaviors.receiveMessage {
        // Incoming requests from endpoint
        case BatchLookup(respondTo) =>
          if (Instant.now().isAfter(lastFullLookup.plusSeconds(updateFreq)))
            okToReturn = false
          if (okToReturn) {
            respondTo ! BatchLookupResult("/users", lookup.values.toList)
          } else {
            queue.enqueue((BatchLookup(respondTo), Instant.now()))
            if (!updateStarted)
              context.self ! UpdateReminder()
          }
          Behaviors.same
        case SingleUserLookup(username, respondTo) =>
          println(Instant.now() + " " + lastFullLookup.plusSeconds(updateFreq))
          if (Instant.now().isAfter(lastFullLookup.plusSeconds(updateFreq)))
            okToReturn = false
          if (okToReturn) {
            lookup.get (username) match {
              case Some (user) =>
                respondTo ! SingleUserLookupResult (user.username, user.plays, user.friends, user.uri)
              case None =>
                respondTo ! NotFound (s"No data about friends or plays by $username is found")
            }
          } else {
            if (!updateStarted)
              context.self ! UpdateReminder()
            queue.enqueue ((SingleUserLookup (username, respondTo), Instant.now ()))
          }
          Behaviors.same
        // Bookkeeping message, that triggeres refreshing lookup table
        case UpdateReminder() =>
          okToReturn = false
          playsCount = 0
          friendsCount = 0
          updateStarted = true
          for (user <- lookup.keys) {
            lookup.remove(user)
          }
          val playsActor = context.spawn (new PlaysActor (playRequestQueue, timeoutSec).create (), s"PlayActor.${Instant.now ().toEpochMilli}")
          val friendsActor = context.spawn (new FriendsActor (friendRequestQueue, timeoutSec).create (), s"FriendsActor.${Instant.now ().toEpochMilli}")
          playsActor ! BatchUserPlayQuery (context.self)
          friendsActor ! BatchUserFriendsQuery (context.self)
          Behaviors.same

        // Processing messages from Play actors
        case SingleUserPlayResult(user, stats) =>
          if (stats.plays.isEmpty) {
            val playsActor = context.spawn (new PlaysActor (playRequestQueue, timeoutSec).create (), s"PlayActor.$user.${UUID.randomUUID()}.")
            playsActor ! SingleUserPlayQuery(user, context.self)
          } else {
            processSinglePlayStat (SingleUserPlayResult (user, stats))
          }
          Behaviors.same
        case BatchUserPlayResult(stats) =>
          processBatchPlays(stats)(context)
          Behaviors.same
        // Processing messages from Friends actor
        case SingleUserFriendsResult(userName, stats) =>
          if (stats.friends.isEmpty) {
            val friendsActor = context.spawn (
              new FriendsActor (friendRequestQueue,  timeoutSec).create (),
              s"FriendsActor.$userName.${UUID.randomUUID()}"
            )
            friendsActor ! SingleUserFriendsQuery(userName, context.self)
          } else {
            processSingleFriendsStat (userName, stats)
          }
          Behaviors.same
        case BatchUserFriendsResult(stats) =>
          processBatchFriends(stats)(context)
          Behaviors.same
      }
    }
  }

  /**
   * When a single user play statistics arrives it is recorded in lookup map and the couter for play stats is increased
   * if enough lookups are processed the requests in queue start processing
   * @param singleUserPlayLookup Message with a single user's listening history
   */
  private def processSinglePlayStat(singleUserPlayLookup: SingleUserPlayResult): Unit = {
    // If friends statistics for this user did not yet arrive we record 0 in place
    val tmp  = lookup.get(singleUserPlayLookup.userName).map(_.friends).getOrElse(0)
    val newUser =  User(
      username = singleUserPlayLookup.userName,
      plays = singleUserPlayLookup.stats.plays.distinct.size,
      friends = tmp,
      s"$prefix/${singleUserPlayLookup.userName}"
    )
    playsCount += 1
    // If enough data is collected start answering queries that are waiting
    if (lookup.contains(singleUserPlayLookup.userName)
        && playsCount.toDouble  / playsRequest >= consistency
        && friendsCount.toDouble / friendsRequest >= consistency
    ) {
      okToReturn = true
      updateStarted = false
      friendsRequest = -1
      playsRequest = -1
      lastFullLookup = Instant.now()
      lookup.put(singleUserPlayLookup.userName, newUser)
      processQueue()
    } else {
      lookup.put(singleUserPlayLookup.userName, newUser)
    }
  }
  /**
   * Same as above, but with friends statistics
    */
  private def processSingleFriendsStat(userName: String, friendsResult: FriendsStat): Unit = {
    // If plays statistics for this user did not yet arrive we record 0 in place
    val tmp  = lookup.get(userName).map(_.plays).getOrElse(0)
    val newUser =  User(
      username = userName,
      friends = friendsResult.friends.size,
      plays = tmp,
      uri = s"/users/$userName"
    )
    friendsCount += 1
    // If enough data is collected start processing queue
    if (
      lookup.contains(userName)
      && playsCount.toDouble  / playsRequest > consistency
      && friendsCount.toDouble / friendsRequest > consistency
    ) {
      okToReturn = true
      updateStarted = false
      lookup.put (userName, newUser)
      friendsRequest =  -1
      playsRequest = -1
      lastFullLookup = Instant.now()
      processQueue()
    } else {
      lookup.put (userName, newUser)
    }
  }

  /**
   * Sending answers to the queries that were waiting for the data to be available
   */
  private def processQueue(): Unit = {
    okToReturn = true
    while (queue.nonEmpty) {
      val candidate = queue.dequeue()
      // Only answer queries that did not timeout (routes take care of that) to avoid sending dead letters
      if (candidate._2.isAfter(lastFullLookup.minusSeconds(askTimeout.getSeconds))) {
        candidate._1 match {
          case BatchLookup(respondTo: ActorRef[BatchLookupResult]) =>
            respondTo ! BatchLookupResult("/users", lookup.values.toList)
          case SingleUserLookup(username, respondTo) =>
            if (lookup.contains(username)) {
              lookup.get(username).foreach(user => {
                respondTo ! SingleUserLookupResult (user.username, user.plays, user.friends, user.uri)
              })
            } else {
              respondTo ! NotFound(s"Could not get any data about: $username")
            }
          case _ =>
            system.log.warn(s"Message ${candidate._1} should not be in the queue for answering")
        }
      }
    }
  }

  /**
   *  Upon receiving a list of users from Play service, start querying each of them individually, to get full stats
   * @param stats List of users for which Play stats is available
   * @param context Akka actor system context
   */
  private def processBatchPlays(stats: PlayStatBatch) (context: ActorContext[LookupMessage]): Unit = {
    if (playsRequest == -1) {
      playsRequest = stats.users.length
      playsCount = 0
      for (user <- stats.users) {
        val pa = context.spawn (new PlaysActor (playRequestQueue, timeoutSec).create (), s"PlayActor${user.username}.${Instant.now ().toEpochMilli}")
        pa ! SingleUserPlayQuery (user.username, context.self)
      }
    }
  }

  /**
   * Same as above but for Friends stats
   * @param stats Friends stat list
   * @param context Akka actor context
   */
  private def processBatchFriends(stats: FriendsStatBatch)(implicit context: ActorContext[LookupMessage]): Unit = {
    if (friendsRequest == -1) {
      friendsRequest = stats.friends.length
      for (user <- stats.friends) {
        val pa = context.spawn (new FriendsActor( friendRequestQueue, 3 * timeoutSec).create (), s"PlayActor${user.username}.${Instant.now ().toEpochMilli}")
        pa ! SingleUserFriendsQuery (user.username, context.self)
      }
    }
  }

  /**
   * Needed for keeping this object single-thread and avoiding exhausting connection poll
   * @param url Binding uri
   * @param port Binding port
   * @return queue to which Http requests are added
   */
  private def createHttpRequestQueue(url: String, port: Int): SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = {
    val pool = Http().cachedHostConnectionPool[Promise[HttpResponse]](url, port)
    Source.queue[(HttpRequest, Promise[HttpResponse])](bufferSize, OverflowStrategy.dropNew)
      .via(pool)
      .toMat(Sink.foreach({
        case (Success(resp), p) => p.success(resp)
        case (Failure(e), p) => p.failure(e)
      }))(Keep.left)
      .run
  }
}
