package com.listenup.challenge.actors

import akka.actor.typed.ActorSystem
import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.model
import akka.http.scaladsl.model.{HttpMethod, HttpRequest, HttpResponse}
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
/** To avoid exhausting connection pools we use queues and proicess http response future, this logic is common for all
 * http requests, so it is moved here
 */
trait SimpleActor  {
  def getResposeFuture(url: String, method: HttpMethod, timeoutInSec: Int, queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])])(implicit executionContext: ExecutionContext ): Future[HttpResponse] = {
    val promise = Promise[HttpResponse]
    val request = HttpRequest().withUri(url).withMethod(method) -> promise
    val responseFuture = queue.offer (request).flatMap {
      case QueueOfferResult.Enqueued => promise.future
      case _ => Future.failed (new RuntimeException ())
    }
    Await.ready(responseFuture, timeoutInSec seconds)
    responseFuture
  }
}
