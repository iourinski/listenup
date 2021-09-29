package com.listenup.challenge.configs

import akka.actor.typed.ActorSystem
/** Config for the main actor and for the Lookup actor */
class ListenUpConfig()(implicit val system:  ActorSystem[_]) extends ServiceConfig {
  override val name: String = "listenup.routes"
}

