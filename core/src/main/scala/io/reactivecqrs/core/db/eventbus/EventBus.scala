package io.reactivecqrs.core.db.eventbus

import io.reactivecqrs.core.db.eventbus.EventBus.MessageToSend
import scalikejdbc._

object EventBus {

  case class MessageToSend[MESSAGE](subscriber: String, message: MESSAGE)

}

class EventBus {


  val settings = ConnectionPoolSettings(
    initialSize = 5,
    maxSize = 20,
    connectionTimeoutMillis = 3000L)

  Class.forName("org.postgresql.Driver")
  ConnectionPool.singleton("jdbc:postgresql://localhost:5432/reactivecqrs", "reactivecqrs", "reactivecqrs", settings)

  def initSchema(): Unit = {
    (new EventBusSchemaInitializer).initSchema()
  }

  def persistMessages(messages: Seq[MessageToSend[Any]]): Unit = {
    DB.autoCommit {implicit session =>
      //TODO optimize, make it one query
      messages.foreach { message =>
        sql"""INSERT INTO messages_to_send (id, message_time, subscriber, message)
             |VALUES (NEXTVAL('events_seq'), current_timestamp, ?, ?)""".stripMargin
          .bind(message.subscriber, message.message.asInstanceOf[Array[Byte]])
      }
    }

  }

  def deleteSentMessages(messages: Seq[Int]): Unit = {
    // TODO optimize SQL query so it will be one query
    DB.autoCommit { implicit session =>
      messages.foreach {message =>
        sql"""DELETE FROM messages_to_send WHERE id = ?"""
          .bind(message)
          .executeUpdate().apply()
      }
    }
  }



}