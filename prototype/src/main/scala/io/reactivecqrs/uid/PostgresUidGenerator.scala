package io.reactivecqrs.uid

import scalikejdbc._

case class IdsPool(from: Long, size: Long)

class PostgresUidGenerator(sequenceName: String) {

  new UidGeneratorSchemaInitializer().initSchema()

  val poolSize = readSequenceStep

  def nextIdsPool: IdsPool = {

    DB.autoCommit { implicit session =>
      val poolFrom = SQL(s"SELECT NEXTVAL('$sequenceName')").map(rs => rs.long(1)).single().apply().getOrElse {
        throw new IllegalStateException("Query returned no values, that should not happen.")
      }
      IdsPool(poolFrom, poolSize)
    }
  }


  private def readSequenceStep: Long = {
    DB.readOnly { implicit session =>
      SQL(s"SELECT increment_by FROM $sequenceName").map(rs => rs.long(1)).single().apply().get
    }
  }

}
