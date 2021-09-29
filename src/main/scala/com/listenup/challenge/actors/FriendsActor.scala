package com.listenup.challenge.actors

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.SourceQueueWithComplete
import com.listenup.challenge.dataschema.{FriendsStat, FriendsStatBatch, JsonFormats, PlayStat}

import scala.concurrent.{ExecutionContext, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

sealed trait FriendsServiceMessages
final case class SingleUserFriendsQuery(userName: String, respondTo: ActorRef[LookupMessage]) extends FriendsServiceMessages
final case class BatchUserFriendsQuery(respondTo: ActorRef[LookupMessage]) extends FriendsServiceMessages

/**
 * Simple actor capable of making http requests to Plays service
 * @param queue SourceQueue for http connection
 * @param timeoutInSec Timeout for a future (different from request timeout)
 * @param system Akka actor system
 */
class FriendsActor(queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])], timeoutInSec: Int = 3)(implicit val system: ActorSystem[_])  extends SimpleActor {
    val prefix = "/friends"

    def create(): Behavior[FriendsServiceMessages] = {
      Behaviors.setup { context =>
        implicit val executionContext: ExecutionContext = context.executionContext
        Behaviors.receiveMessage {
          case SingleUserFriendsQuery (user, respondTo) =>
            val responseFuture = getResposeFuture(s"$prefix/$user", HttpMethods.GET,timeoutInSec, queue)
            responseFuture.onComplete[Unit] {
              case Success (response) =>
                reportSingleResult (user, response, respondTo)
              case Failure (exception) =>
                respondTo ! SingleUserFriendsResult(user, FriendsStat("", List()))
                reportFailure (exception, s"Lookup for  $user")
            }
            Behaviors.stopped
          case BatchUserFriendsQuery (respondTo) =>
            val responseFuture = getResposeFuture(s"$prefix", HttpMethods.GET,timeoutInSec, queue)
            responseFuture.onComplete[Unit] {
              case Success (response) =>
                reportMultipleResult(response, respondTo)
              case Failure (exception) =>
                reportFailure (exception, "Batch friends query")
            }
            Behaviors.stopped
        }
      }
    }
  private def reportSingleResult(user: String, resp: HttpResponse, respondTo: ActorRef[SingleUserFriendsResult])
    (implicit context: ExecutionContext): Unit = {
    val data = Unmarshal(resp.entity).to[String]
    data.map { d =>
      val js = spray.json.JsonParser.apply(d)
      import JsonFormats._
      val friendsStat = js.convertTo[FriendsStat]
      respondTo ! SingleUserFriendsResult(userName = user, friendsStat)
    }
  }
  private def reportMultipleResult(resp: HttpResponse, respondTo: ActorRef[BatchUserFriendsResult] )
    (implicit context: ExecutionContext): Unit = {
    val data = Unmarshal(resp.entity).to[String]
    data.map { d =>
      val js = spray.json.JsonParser.apply(d)
      import JsonFormats._
      respondTo ! BatchUserFriendsResult(js.convertTo[FriendsStatBatch])
    }
  }
  private def reportFailure(ex: Throwable, query: String)(implicit context: ExecutionContext): Unit = {
    system.log.error(s"Friends service, $query failed:  ${ex.getMessage}")
  }
}
