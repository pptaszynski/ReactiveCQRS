package io.reactivecqrs.core.documentstore

import java.sql.{Connection, ResultSet}

import io.mpjsons.MPJsons
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import scalikejdbc.{DB, DBSession}

import scala.collection.mutable
import scala.reflect.runtime.universe._
import scalikejdbc._

sealed trait PostgresDocumentStoreTrait[T <: AnyRef, M <: AnyRef] {

  implicit val t: TypeTag[T]
  implicit val m: TypeTag[M]

  val tableName: String
  val mpjsons: MPJsons
  val cache: DocumentStoreCache[T, M]

  protected var localCache: Map[Long, Option[VersionedDocument[T, M]]] = Map.empty

  if (!tableName.matches( """[a-zA-Z0-9\_]+""")) {
    throw new IllegalArgumentException("Invalid table name, only alphanumeric characters and underscore allowed")
  }

  protected final val projectionTableName = "projection_" + tableName

  protected val tableNameSQL = SQLSyntax.createUnsafely(projectionTableName)

  protected def SELECT_DOCUMENT_BY_PATH(path: String) = s"SELECT id, version, document, metadata FROM $projectionTableName WHERE document #>> '{$path}' = ?"

  protected def SELECT_DOCUMENT_BY_PATH_WITH_ONE_OF_THE_VALUES(path: String, values: Set[String]) =
    s"SELECT id, version, document, metadata FROM $projectionTableName WHERE document #>> '{$path}' in (${values.map("'"+_+"'").mkString(",")})"

  init()

  protected def init(): Unit = {
    createTableIfNotExists()
  }

  protected def createTableIfNotExists(): Unit = DB.autoCommit { implicit session =>
    sql"""CREATE TABLE IF NOT EXISTS ${tableNameSQL} (
          |id BIGINT NOT NULL PRIMARY KEY,
          |version INT NOT NULL,
          |document JSONB NOT NULL, metadata JSONB NOT NULL)"""
      .stripMargin.execute().apply()
  }

  def dropTable(): Unit = DB.autoCommit { implicit session =>
    sql"""DROP TABLE ${tableNameSQL}"""
      .stripMargin.execute().apply()
  }

  def overwriteDocument(key: Long, document: T, metadata: M)(implicit session: DBSession): Unit = {

    inSession { implicit session =>
      val updated = sql"UPDATE ${tableNameSQL} SET document = ?::JSONB, metadata = ?::JSONB, version = version + 1 WHERE id = ? RETURNING version"
        .bind(mpjsons.serialize[T](document), mpjsons.serialize[M](metadata), key)
        .map(_.int(1)).list().apply()

      if (updated.size != 1) {
        throw new IllegalStateException("Expected 1, updated " + updated + " records")
      } else {
        putInCache(key, Some(VersionedDocument(updated.head, document, metadata)), NotInCache)
      }
    }
  }



  def getDocument(key: Long)(implicit session: DBSession = null): Option[Document[T, M]] = {

    cache.get(key) match {
      case InCache(VersionedDocument(version, document, metadata)) => Some(Document(document, metadata))
      case InCacheEmpty => None
      case NotInCache =>
        inSession { implicit session =>
          val loaded = sql"SELECT version, document, metadata FROM ${tableNameSQL} WHERE id = ?"
            .bind(key).map(rs => {
            VersionedDocument[T, M](rs.int(1), mpjsons.deserialize[T](rs.string(2)), mpjsons.deserialize[M](rs.string(3)))
          }).single().apply()
          loaded match {
            case Some(doc) =>
              putInCache(key, Some(doc), NotInCache)
              Some(Document(doc.document, doc.metadata))
            case None =>
              putInCache(key, None, NotInCache)
              None
          }
        }
      }
  }

  def removeDocument(key: Long)(implicit session: DBSession): Unit = {

    inSession { implicit session =>
      sql"DELETE FROM ${tableNameSQL} WHERE id = ?"
        .bind(key).executeUpdate().apply()
      cache.remove(key)
      localCache -= key
    }

  }

  def findDocumentByPath(path: Seq[String], value: String)(implicit session: DBSession = null): Map[Long, Document[T, M]] = {
    inSession { implicit session =>
      val loaded = SQL(SELECT_DOCUMENT_BY_PATH(path.mkString(",")))
        .bind(value).map(rs => rs.long(1) -> VersionedDocument[T, M](rs.int(2), mpjsons.deserialize[T](rs.string(3)), mpjsons.deserialize[M](rs.string(4))))
        .list().apply()

      loaded.foreach(t => putInCache(t._1, Some(t._2), NotInCache))
      loaded.map({case (key, value) => (key, Document[T, M](value.document, value.metadata))}).toMap
    }
  }


  //TODO WARNING change to proper sql construction
  def findDocumentsByPathWithOneOfTheValues(path: Seq[String], values: Set[String])(implicit session: DBSession = null): Map[Long, Document[T, M]] = {

    if(values.nonEmpty) {
      inSession { implicit session =>
        val loaded = SQL(SELECT_DOCUMENT_BY_PATH_WITH_ONE_OF_THE_VALUES(path.mkString(","), values))
          .map(rs => rs.long(1) -> VersionedDocument[T, M](rs.int(2), mpjsons.deserialize[T](rs.string(3)), mpjsons.deserialize[M](rs.string(4))))
          .list().apply()

        loaded.foreach(t => putInCache(t._1, Some(t._2), NotInCache))
        loaded.map({case (key, value) => (key, Document[T, M](value.document, value.metadata))}).toMap
      }
    } else {
      Map.empty
    }

  }


  def findDocumentByObjectInArray[V](arrayPath: Seq[String], objectPath: Seq[String], value: V)(implicit session: DBSession = null): Map[Long, Document[T, M]] = {
    findDocumentByObjectInArray("document", arrayPath, objectPath, value)
  }

  def findDocumentByMetadataObjectInArray[V](arrayPath: Seq[String], objectPath: Seq[String], value: V)(implicit session: DBSession = null): Map[Long, Document[T, M]] = {
    findDocumentByObjectInArray("metadata", arrayPath, objectPath, value)
  }

  protected def findDocumentByObjectInArray[V](columnName: String, array: Seq[String], objectPath: Seq[String], value: V)(implicit session: DBSession): Map[Long, Document[T, M]] = {

    def QUERY(arrayPath: String, path: String) =
      s"SELECT id, version, document, metadata FROM $projectionTableName WHERE $columnName #> '$arrayPath' @> '[$path]'"

    def makeJson(path: Seq[String], value: V): String =
      path match {
        case head :: tail => "{\"" + head + "\":" + makeJson(tail, value) + "}"
        case Nil => value match {
          case s: String => "\"" + s + "\""
          case anything => anything.toString
        }
      }

    inSession { implicit session =>
      val loaded = SQL(QUERY(array.mkString("{", ",", "}"), makeJson(objectPath, value)))
        .map(rs => rs.long(1) -> VersionedDocument[T, M](rs.int(2), mpjsons.deserialize[T](rs.string(3)), mpjsons.deserialize[M](rs.string(4))))
        .list().apply()

      loaded.foreach(t => putInCache(t._1, Some(t._2), NotInCache))
      loaded.map({case (key, v) => (key, Document[T, M](v.document, v.metadata))}).toMap
    }
  }


  def findAll()(implicit session: DBSession = null): Map[Long, Document[T, M]] = {
    val loaded = inSession { implicit session =>
      sql"SELECT id, version, document, metadata FROM ${tableNameSQL}"
          .map(rs => rs.long(1) -> VersionedDocument[T,M](rs.int(2), mpjsons.deserialize[T](rs.string(3)), mpjsons.deserialize[M](rs.string(4))))
        .list.apply()
    }
    loaded.foreach(t => putInCache(t._1, Some(t._2), NotInCache))
    loaded.toMap.mapValues(v => Document(v.document, v.metadata))
  }

  def countAll()(implicit session: DBSession = null): Int = {
    inSession { implicit session =>
      sql"SELECT count(*) FROM ${tableNameSQL}"
        .map(rs => rs.int(1))
        .single().apply().get
    }
  }


  def getDocuments(keys: List[Long])(implicit session: DBSession = null): Map[Long, Document[T, M]] = {

    val keysSet: Set[Long] = keys.toSet
    val fromCache = cache.getAll(keysSet)
    val fromCacheFound = fromCache.collect {
      case (k, InCache(value)) => (k, value)
    }
    val fromCacheFoundOrEmpty = fromCache.collect {
      case (k, InCache(value)) => k
      case (k, InCacheEmpty) => k
    }

    val toLoad = keysSet -- fromCacheFoundOrEmpty

    val fromCacheFoundValues = fromCacheFound.map {case (key, value) => (key, Document(value.document, value.metadata))}

    if (toLoad.isEmpty) {
      fromCacheFoundValues
    } else {
      val loaded = inSession { implicit session =>
        sql"SELECT id, version, document, metadata FROM ${tableNameSQL} WHERE id IN (${toLoad})"
          .map(rs => {
            rs.long(1) -> VersionedDocument[T, M](rs.int(2), mpjsons.deserialize[T](rs.string(3)), mpjsons.deserialize[M](rs.string(4)))
          }).list().apply()
      }
      toLoad.foreach(id => {
        putInCache(id, loaded.find(_._1 == id).map(_._2), NotInCache)
      })
      fromCacheFoundValues ++ loaded.toMap.mapValues(v => Document(v.document, v.metadata))
    }
  }


  protected def inSession[RETURN_TYPE](body: DBSession => RETURN_TYPE)(implicit session: DBSession): RETURN_TYPE = {
    if(session == null) {
      DB.readOnly { s =>
        body(s)
      }
    } else {
      body(session)
    }
  }

  protected def putInCache(key: Long, document: Option[VersionedDocument[T, M]], oldValue: CacheEntry[VersionedDocument[T, M]]): Unit = {

    val toReplace: CacheEntry[VersionedDocument[T, M]] = oldValue match {
      case e: InCache[_] => e
      case InCacheEmpty => InCacheEmpty
      case NotInCache => localCache.get(key) match {
        case Some(Some(entry)) => InCache(entry)
        case Some(None) => InCacheEmpty
        case None => cache.get(key)
      }
    }

    toReplace match {
      case InCache(entry) => document match {
        case Some(doc) =>
          if(entry.version >= doc.version) {
            // newer version already inplace
            localCache += key -> Some(entry)
          } else {
            if(cache.replace(key, Some(entry), document)) {
              // Hurray
              localCache += key -> document
            } else {
              putInCache(key, document, cache.get(key))
            }
          }
        case None =>
          if(cache.replace(key, None, document)) {
            // Hurray
            localCache += key -> document
          } else {
            putInCache(key, document, cache.get(key))
          }
      }
      case InCacheEmpty =>
        if(cache.replace(key, None, document)) {
          // Hurray
          localCache += key -> document
        } else {
          putInCache(key, document, cache.get(key))
        }
      case NotInCache =>
        cache.putIfAbsent(key, document) match {
          case entry: InCache[_] => putInCache(key, document, entry)
          case InCacheEmpty => putInCache(key, document, InCacheEmpty)
          case NotInCache =>
            // Hurray
            localCache += key -> document
        }
    }
  }


  protected def getFromCacheOrDB(key: Long)(implicit session: DBSession): Option[VersionedDocument[T, M]] = {
    localCache.getOrElse(key, cache.get(key).getOrElse(sql"SELECT version, document, metadata FROM ${tableNameSQL} WHERE id = ?"
      .bind(key).map(rs => {
      VersionedDocument[T, M](rs.int(1), mpjsons.deserialize[T](rs.string(2)), mpjsons.deserialize[M](rs.string(3)))
    }).single().apply()))
  }

}

class PostgresDocumentStore[T <: AnyRef, M <: AnyRef](val tableName: String, val mpjsons: MPJsons, val cache: DocumentStoreCache[T, M])(implicit val t: TypeTag[T], val m: TypeTag[M])
  extends DocumentStore[T, M] with PostgresDocumentStoreTrait[T, M] {

  override def insertDocument(key: Long, document: T, metadata: M)(implicit session: DBSession): Unit = {
    inSession { implicit session =>
      sql"INSERT INTO ${tableNameSQL} (id, version, document, metadata) VALUES (?, 1, ?::jsonb, ?::jsonb)"
        .bind(key, mpjsons.serialize(document), mpjsons.serialize(metadata))
        .executeUpdate().apply()
      putInCache(key, Some(VersionedDocument(1, document, metadata)), NotInCache)
    }
  }

  // Optimistic locking update
  def updateDocument(key: Long, modify: Option[Document[T, M]] => Document[T, M])(implicit session: DBSession): Document[T, M] = {

    inSession { implicit session =>
      getFromCacheOrDB(key) match {
        case None =>
          val modified = modify(None)
          insertDocument(key, modified.document, modified.metadata)
          modified
        case Some(VersionedDocument(version, document, metadata)) =>
          val modified = modify(Some(Document(document, metadata)))

          val updated = sql"UPDATE ${tableNameSQL} SET version = ?, document = ?::JSONB, metadata = ?::JSONB WHERE id = ? AND version = ?"
            .bind(version + 1, mpjsons.serialize[T](modified.document), mpjsons.serialize[M](modified.metadata), key, version)
            .map(_.int(1)).single().executeUpdate().apply()

          if(updated == 0) {
            updateDocument(key, modify)
          } else {
            putInCache(key, Some(VersionedDocument(version + 1, modified.document, modified.metadata)), NotInCache)
            modified
          }
      }
    }
  }


  def clearAllData()(implicit session: DBSession): Unit = {
    inSession { implicit session =>
      sql"TRUNCATE TABLE ${tableNameSQL}".executeUpdate().apply()
      localCache = Map.empty
      cache.clear()
    }
  }

}

class PostgresDocumentStoreAutoId[T <: AnyRef, M <: AnyRef](val tableName: String, val mpjsons: MPJsons, val cache: DocumentStoreCache[T, M])(implicit val t: TypeTag[T], val m: TypeTag[M])
  extends DocumentStoreAutoId[T, M] with PostgresDocumentStoreTrait[T, M] {

  protected final lazy val sequenceName = "sequence_" + tableName

  protected final lazy val sequenceNameSQL = SQLSyntax.createUnsafely(sequenceName)


  override protected def createTableIfNotExists(): Unit = {
    super.createTableIfNotExists()
    try {
      DB.autoCommit { implicit session =>
        sql"CREATE SEQUENCE ${sequenceNameSQL} START 1"
          .executeUpdate().apply()
      }
    } catch {
      case e: PSQLException if e.getServerErrorMessage.toString.contains("already exists")=> () // IF NOT EXIST workaround
    }
  }

  override def dropTable(): Unit = DB.autoCommit { implicit session =>
    sql"""DROP TABLE ${tableNameSQL}"""
      .stripMargin.execute().apply()

    sql"""DROP SEQUENCE ${sequenceNameSQL}"""
      .stripMargin.execute().apply()

  }

  override def insertDocument(document: T, metadata: M)(implicit session: DBSession): Long = {
    inSession {implicit session =>
      val key = sql"INSERT INTO ${tableNameSQL} (id, version, document, metadata) VALUES (nextval('${sequenceNameSQL}'), 1, ?::jsonb, ?::jsonb) RETURNING currval('${sequenceNameSQL}')"
          .bind(mpjsons.serialize(document), mpjsons.serialize(metadata))
          .map(_.long(1)).single().apply().get
      putInCache(key, Some(VersionedDocument(1, document, metadata)), NotInCache)
      key
    }
  }

  // Optimistic locking update
  def updateDocument(key: Long, modify: Option[Document[T, M]] => Document[T, M])(implicit session: DBSession): Document[T, M] = {

    inSession { implicit session =>
      getFromCacheOrDB(key) match {
        case None => throw new IllegalStateException(s"Document for key $key not found!")
        case Some(VersionedDocument(version, document, metadata)) =>
          val modified = modify(Some(Document(document, metadata)))

          val updated = sql"UPDATE ${tableNameSQL} SET version = ?, document = ?::JSONB, metadata = ?::JSONB WHERE id = ? AND version = ?"
            .bind(version + 1, mpjsons.serialize[T](modified.document), mpjsons.serialize[M](modified.metadata), key, version)
            .map(_.int(1)).single().executeUpdate().apply()

          if(updated == 0) {
            updateDocument(key, modify)
          } else {
            putInCache(key, Some(VersionedDocument(version + 1, modified.document, modified.metadata)), NotInCache)
            modified
          }
      }
    }
  }

  def clearAllData()(implicit session: DBSession): Unit = {
    inSession { implicit session =>
      sql"TRUNCATE TABLE ${tableNameSQL}".executeUpdate().apply()
      sql"ALTER SEQUENCE ${sequenceNameSQL} RESTART WITH 1".executeUpdate().apply()
      localCache = Map.empty
      cache.clear()
    }
  }
}

