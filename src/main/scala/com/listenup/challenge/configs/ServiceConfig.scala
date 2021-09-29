package com.listenup.challenge.configs

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
/** We use config files to make system more flexible this trait is used for parsing/accessing configs parameters
 * unique for this application (and that are separate from Akka configuration)
 */
trait ServiceConfig {
  protected val system: ActorSystem[_]
  protected val name: String
  protected val rawConfig: Config = system.settings.config
  protected lazy val config: Config = rawConfig.getConfig(name)

  def getPort: Int = {
    config.getInt("port")
  }
  def getUrl: String = {
    config.getString("url")
  }

  /**
   * Getter in case if we need to get some custom parameters which are not extracted by specified class
   * @return
   */
  def getFullConfig: Config = {
    config
  }
}
