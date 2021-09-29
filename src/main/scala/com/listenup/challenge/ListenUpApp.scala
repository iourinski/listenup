package com.listenup.challenge

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.listenup.challenge.actors.LookupActor
import com.listenup.challenge.configs.ListenUpConfig
import com.listenup.challenge.routes.ListenUpRoutes

import scala.util.Failure
import scala.util.Success

/** Standard Lightbend runner (provided in template) */
object ListenUpApp {
  private def startHttpServer (routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext
    val serviceConfig = new ListenUpConfig
    val futureBinding = Http ().newServerAt (serviceConfig.getUrl, serviceConfig.getPort).bind (routes)
    futureBinding.onComplete {
      case Success (binding) =>
        val address = binding.localAddress
        system.log.info ("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure (ex) =>
        system.log.error ("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate ()
    }
  }

  def main (args: Array[String]): Unit = {
      val rootBehavior = Behaviors.setup[Nothing] { context =>
        val lookupActor = context.spawn (new LookupActor ()(context.system).create (), "UserRegistryActor")
        context.watch (lookupActor)
        val routes = new ListenUpRoutes (lookupActor)(context.system)
        startHttpServer (routes.userRoutes)(context.system)
        Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "ListenUpServer")
  }
}
