package com.listenup.challenge.dataschema

import com.listenup.challenge.routes.BatchLookupResult
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/** Implicit formats for matshalling/unmarshalling of the objects passed through http calls */
object JsonFormats {
  import DefaultJsonProtocol._
  implicit val simpleUserJsonFormat: RootJsonFormat[SimpleUser] = jsonFormat2(SimpleUser)
  implicit val userPlaysJsonFormat: RootJsonFormat[PlayStat] = jsonFormat2 (PlayStat)
  implicit val userFriendsJsonFormat: RootJsonFormat[FriendsStat] = jsonFormat2(FriendsStat)
  implicit val userFriendsBatchJsonFormat: RootJsonFormat[FriendsStatBatch] = jsonFormat2(FriendsStatBatch)
  implicit val usersPlaysJsonFormat: RootJsonFormat[PlayStatBatch] = jsonFormat2 (PlayStatBatch)
  implicit val userJsonFormat: RootJsonFormat[User] = jsonFormat4(User)
  implicit val userStatsJsonFormat: RootJsonFormat[BatchLookupResult] = jsonFormat2(BatchLookupResult)
}
