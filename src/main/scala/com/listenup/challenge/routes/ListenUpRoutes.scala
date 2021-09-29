package com.listenup.challenge.routes

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.listenup.challenge.actors.{BatchLookup, LookupMessage, SingleUserLookup}
import com.listenup.challenge.configs.ListenUpConfig
import com.listenup.challenge.dataschema.User

import scala.concurrent.Future
/** Messages returned by the server */
trait UserMessages
final case class BatchLookupResult(uri: String, users: Seq[User]) extends UserMessages
final case class SingleUserLookupResult(username: String, plays: Int, friends: Int, uri: String) extends UserMessages
final case class NotFound(message: String) extends UserMessages
/** Routing calls, light modification of provided template */
class ListenUpRoutes(lookup: ActorRef[LookupMessage])(implicit val system: ActorSystem[_]) {
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import com.listenup.challenge.dataschema.JsonFormats._
  val listenUpConfig = new ListenUpConfig()
  private implicit val timeout: Timeout = Timeout.create(listenUpConfig.getFullConfig.getDuration("ask-timeout"))

  def getUsers: Future[UserMessages] =
     lookup.ask(BatchLookup)
  def getUser(user: String): Future[UserMessages] = {
    lookup.ask(SingleUserLookup(user, _))
  }
  val userRoutes: Route =
    pathPrefix("users") {
      concat(
        pathEnd {
          concat(
            get {
              val userRes: Future[UserMessages] = getUsers
              onSuccess(userRes) {
                case  BatchLookupResult(users, userStats) =>
                  complete(BatchLookupResult(users, userStats))
                case  NotFound(message) =>
                  complete(StatusCodes.NotFound, message)
                case _ =>
                  complete(StatusCodes.custom(500,  "Looks like there was a problem with your request"))
              }
            }
          )
        },
        path(Segment) { name =>
          concat (
            get {
              val res = getUser(name)
              rejectEmptyResponse {
                onSuccess (res) {
                  case SingleUserLookupResult(user, plays, friends, uri) =>
                    complete (User(user, plays, friends, uri))
                  case NotFound(message) =>
                    complete(StatusCodes.NotFound, message + "\n")
                  case _ =>
                    complete(StatusCodes.custom(500,  "Looks like there was a problem with your request"))
                }
              }
            }
          )
        }
      )
    }
}
