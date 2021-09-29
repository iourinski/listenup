package com.listenup.challenge.dataschema

import scala.collection.immutable

final case class Users(users: immutable.Seq[String], uri: String)

