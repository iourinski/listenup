package com.listenup.challenge.configs

import akka.actor.typed.ActorSystem
/** Config for actor that queries external service "Plays" */
class PlaysConfig () (implicit val system:  ActorSystem[_]) extends ServiceConfig {
    override val name = "listenup.external.plays"
}
