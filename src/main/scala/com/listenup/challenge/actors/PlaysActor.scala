package com.listenup.challenge.actors

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.SourceQueueWithComplete

import scala.concurrent.{ ExecutionContext, Promise}
import scala.util.{Failure, Success}
import com.listenup.challenge.dataschema.{JsonFormats, PlayStat, PlayStatBatch}
import scala.language.postfixOps

/** Only two types of requests are available */
sealed  trait PlayServiceMessages
final case class SingleUserPlayQuery(userName: String, respondTo: ActorRef[SingleUserPlayResult]) extends PlayServiceMessages
final case class BatchUserPlayQuery(respondTo: ActorRef[LookupMessage]) extends PlayServiceMessages

/**
 * Simple actor for communicating with Plays service
 * @param queue SourceQueue for efficient use of connection poll
 * @param timeoutInSec Timeout for a future (shorter that request timeout on front)
 * @param system Akka actor system
 */
class PlaysActor (queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])], timeoutInSec: Int) (implicit val system: ActorSystem[_]) extends SimpleActor {
  private val prefix = "/plays"

  def create(): Behavior[PlayServiceMessages] = {
    Behaviors.setup { context =>
      implicit val executionContext: ExecutionContext = context.executionContext
      Behaviors.receiveMessage {
        case SingleUserPlayQuery (user, respondTo) =>
          val responseFuture = getResposeFuture (s"$prefix/$user", HttpMethods.GET, timeoutInSec, queue)
          responseFuture.onComplete[Unit] {
            case Success (response) =>
              reportSingleResult (user, response, respondTo)
            case Failure (exception) =>
              respondTo ! SingleUserPlayResult (user, PlayStat ("", List ()))
              reportFailure (exception, user)
          }
          Behaviors.stopped

        case BatchUserPlayQuery (respondTo) =>
          val responseFuture = getResposeFuture (s"$prefix", HttpMethods.GET, timeoutInSec, queue)
          responseFuture.onComplete[Unit] {
            case Success (response) =>
              reportMultipleResult (response, respondTo)
            case Failure (exception) =>
              reportFailure (exception, "batch query")
          }
          Behaviors.stopped
      }
    }
  }
  private def reportSingleResult(user: String, resp: HttpResponse, respondTo: ActorRef[SingleUserPlayResult])(implicit context: ExecutionContext): Unit = {
    val data = Unmarshal(resp.entity).to[String]
    data.map { d =>
      val js = spray.json.JsonParser.apply(d)
      import JsonFormats._
      val playStat = js.convertTo[PlayStat]
      respondTo ! SingleUserPlayResult(userName = user, playStat)
    }
  }
  private def reportMultipleResult(resp: HttpResponse, respondTo: ActorRef[LookupMessage])
    (implicit context: ExecutionContext): Unit = {
    val data = Unmarshal(resp.entity).to[String]
    data.map { d =>
      val js = spray.json.JsonParser.apply(d)
      import JsonFormats._
      respondTo ! BatchUserPlayResult(js.convertTo[PlayStatBatch])
    }
  }
  private def reportFailure(ex: Throwable, query: String)(implicit context: ExecutionContext): Unit = {
     system.log.error(s"Play service, query $query:  ${ex.getMessage}")
  }
}
