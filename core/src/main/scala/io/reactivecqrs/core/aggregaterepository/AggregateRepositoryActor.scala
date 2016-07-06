package io.reactivecqrs.core.aggregaterepository

import java.io.{PrintWriter, StringWriter}
import java.time.Instant

import io.reactivecqrs.core.commandhandler.ResultAggregator
import io.reactivecqrs.core.eventstore.EventStoreState
import io.reactivecqrs.core.util.ActorLogging
import io.reactivecqrs.api._
import akka.actor.{Actor, ActorRef, PoisonPill}
import io.reactivecqrs.api.id.{AggregateId, CommandId, UserId}
import io.reactivecqrs.core.eventbus.EventsBusActor.{PublishEvents, PublishEventAck}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect._
import scala.reflect.runtime.universe._
import scala.util.{Failure, Success, Try}

object AggregateRepositoryActor {
  case class GetAggregateRoot(respondTo: ActorRef)

  case class PersistEvents[AGGREGATE_ROOT](respondTo: ActorRef,
                                            commandId: CommandId,
                                            userId: UserId,
                                            expectedVersion: AggregateVersion,
                                            timestamp: Instant,
                                            events: Seq[Event[AGGREGATE_ROOT]])


  case class EventsPersisted[AGGREGATE_ROOT](events: Seq[IdentifiableEvent[AGGREGATE_ROOT]])

  case object ResendPersistedMessages
}


class AggregateRepositoryActor[AGGREGATE_ROOT:ClassTag:TypeTag](aggregateId: AggregateId,
                                                                eventStore: EventStoreState,
                                                                eventsBus: ActorRef,
                                                                eventHandlers: AGGREGATE_ROOT => PartialFunction[Any, AGGREGATE_ROOT],
                                                                initialState: () => AGGREGATE_ROOT,
                                                                singleReadForVersionOnly: Option[AggregateVersion]) extends Actor with ActorLogging {

  import AggregateRepositoryActor._


  private var version: AggregateVersion = AggregateVersion.ZERO
  private var aggregateRoot: AGGREGATE_ROOT = initialState()
  private val aggregateType = AggregateType(classTag[AGGREGATE_ROOT].toString)

  private var eventsToPublish = List[IdentifiableEventNoAggregateType[AGGREGATE_ROOT]]()


  private def assureRestoredState(): Unit = {
    //TODO make it future
    version = AggregateVersion.ZERO
    aggregateRoot = initialState()
    eventStore.readAndProcessEvents[AGGREGATE_ROOT](aggregateId, singleReadForVersionOnly)(handleEvent)

    eventsToPublish = eventStore.readEventsToPublishForAggregate[AGGREGATE_ROOT](aggregateId)
  }

  private def resendEventsToPublish(): Unit = {
    if(eventsToPublish.nonEmpty) {
      eventsBus ! PublishEvents(aggregateType, eventsToPublish.map(e => IdentifiableEvent(e.eventId, aggregateType, aggregateId, e.version, e.event, e.userId, e.timestamp)), aggregateId, version, Option(aggregateRoot))
    }
  }

  assureRestoredState()
  resendEventsToPublish()

  context.system.scheduler.schedule(60.seconds, 60.seconds, self, ResendPersistedMessages)(context.dispatcher)

  private def stackTraceToString(e: Throwable) = {
    val sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  override def receive = logReceive {
    case ep: EventsPersisted[_] =>
      if(ep.asInstanceOf[EventsPersisted[AGGREGATE_ROOT]].events.exists(_.event.isInstanceOf[UndoEvent[_]]) ||
        ep.asInstanceOf[EventsPersisted[AGGREGATE_ROOT]].events.exists(_.event.isInstanceOf[DuplicationEvent[_]])) {
        // In case of those events it's easier to re read past events
        assureRestoredState()
      } else {
        ep.asInstanceOf[EventsPersisted[AGGREGATE_ROOT]].events.foreach(eventIdentifier => handleEvent(eventIdentifier.event, aggregateId, false))
      }
      eventsBus ! PublishEvents(aggregateType, ep.asInstanceOf[EventsPersisted[AGGREGATE_ROOT]].events, aggregateId, version, Option(aggregateRoot))
    case ee: PersistEvents[_] =>

      val result = ee.asInstanceOf[PersistEvents[AGGREGATE_ROOT]].events.foldLeft(Right(aggregateRoot).asInstanceOf[Either[(Exception, Event[AGGREGATE_ROOT]), AGGREGATE_ROOT]])((aggEither, event) => {
        aggEither match {
          case Right(agg) => tryToHandleEvent(event, false, agg)
          case f: Left[_, _] => f
        }
      })

      result match {
        case s: Right[_, _] => handlePersistEvents(ee.asInstanceOf[PersistEvents[AGGREGATE_ROOT]])
        case Left((exception, event)) =>
          ee.respondTo ! EventHandlingError(event.getClass.getSimpleName, stackTraceToString(exception), ee.commandId)
          log.error(exception, "Error handling event")
      }

    case GetAggregateRoot(respondTo) =>
      receiveReturnAggregateRoot(respondTo)
    case PublishEventAck(event) =>
      markPublishedEvent(event)
    case ResendPersistedMessages =>
      resendEventsToPublish()
  }


  private def handlePersistEvents(eventsEnvelope: PersistEvents[AGGREGATE_ROOT]): Unit = {
    if (eventsEnvelope.expectedVersion == version) {
      persist(eventsEnvelope)(respond(eventsEnvelope.respondTo))
    } else {
      eventsEnvelope.respondTo ! AggregateConcurrentModificationError(aggregateId, aggregateType.simpleName, eventsEnvelope.expectedVersion, version)
    }

  }

  private def receiveReturnAggregateRoot(respondTo: ActorRef): Unit = {
    if(version == AggregateVersion.ZERO) {
      respondTo ! Failure(new NoEventsForAggregateException(aggregateId))
    } else {
      respondTo ! Success(Aggregate[AGGREGATE_ROOT](aggregateId, version, Some(aggregateRoot)))
    }

    if(singleReadForVersionOnly.isDefined) {
      self ! PoisonPill
    }

  }


  private def persist(eventsEnvelope: PersistEvents[AGGREGATE_ROOT])(afterPersist: Seq[Event[AGGREGATE_ROOT]] => Unit): Unit = {
    //Future { FIXME this future can broke order in which events are stored
      val eventsWithIds = eventStore.persistEvents(aggregateId, eventsEnvelope.asInstanceOf[PersistEvents[AnyRef]])
      var mappedEvents = 0
      self ! EventsPersisted(eventsWithIds.map { case (event, eventId) =>
        val eventVersion = eventsEnvelope.expectedVersion.incrementBy(mappedEvents + 1)
        mappedEvents += 1
        IdentifiableEvent(eventId, AggregateType(event.aggregateRootType.toString), aggregateId, eventVersion, event, eventsEnvelope.userId, eventsEnvelope.timestamp)
      })
      afterPersist(eventsEnvelope.events)
//    } onFailure {
//      case e: Exception => throw new IllegalStateException(e)
//    }
  }

  private def respond(respondTo: ActorRef)(events: Seq[Event[AGGREGATE_ROOT]]): Unit = {
    respondTo ! ResultAggregator.AggregateModified
  }

  private def tryToHandleEvent(event: Event[AGGREGATE_ROOT], noopEvent: Boolean, tmpAggregateRoot: AGGREGATE_ROOT): Either[(Exception, Event[AGGREGATE_ROOT]), AGGREGATE_ROOT] = {
    if(!noopEvent) {
      try {
        Right(eventHandlers(tmpAggregateRoot)(event))
      } catch {
        case e: Exception =>
          log.error("Error while handling event tryout : " + event)
          Left((e, event))
      }
    } else {
      Right(tmpAggregateRoot)
    }
  }

  private def handleEvent(event: Event[AGGREGATE_ROOT], aggId: AggregateId, noopEvent: Boolean): Unit = {
    if(!noopEvent) {
      try {
        aggregateRoot = eventHandlers(aggregateRoot)(event)
      } catch {
        case e: Exception =>
          log.error("Error while handling event: " + event)
          throw e;
      }
    }

    if(aggregateId == aggId) { // otherwise it's event from base aggregate we don't want to count
      version = version.increment
    }
  }

  def markPublishedEvent(eventId: Long): Unit = {
    import context.dispatcher
    eventsToPublish = eventsToPublish.filterNot(e => e.eventId == eventId)

    Future { // Fire and forget
      eventStore.deletePublishedEventsToPublish(List(eventId))
    } onFailure {
      case e: Exception => throw new IllegalStateException(e)
    }
  }


}