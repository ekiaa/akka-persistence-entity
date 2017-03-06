package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.ActorContext

trait PersistenceEntitySystem {

  def build(entityId: EntityId): Entity

  def recovery(snapshot: Entity): Entity

  def sendMessage(message: Message)(implicit context: ActorContext): Unit

}


trait Entity {

  def entityId: EntityId

  def init(): Entity

  def handleRequest(request: Request): Reaction

  def handleResponse(response: Response): Reaction

  def handleEvent(event: Event): Reaction

}

trait EntityId {

  def persistenceId: String

}

trait Message {

  val id: String

  val correlationId: String

  val requesterId: EntityId

  val reactorId: EntityId

}

trait Request

trait Response

trait Event

case class RequestMessage(id: String = UUID.randomUUID().toString,
                          correlationId: String = UUID.randomUUID().toString,
                          requesterId: EntityId,
                          reactorId: EntityId,
                          request: Request
                         ) extends Message

case class ResponseMessage(id: String = UUID.randomUUID().toString,
                           correlationId: String,
                           requesterId: EntityId,
                           reactorId: EntityId,
                           response: Response
                          ) extends Message

case class EventMessage(id: String = UUID.randomUUID().toString,
                        correlationId: String,
                        requesterId: EntityId,
                        reactorId: EntityId,
                        event: Event) extends Message

case class EventConfirmed(id: String = UUID.randomUUID().toString,
                          correlationId: String,
                          requesterId: EntityId,
                          reactorId: EntityId,
                          eventId: String) extends Message

case class EventProcessed(id: String = UUID.randomUUID().toString,
                          correlationId: String,
                          requesterId: EntityId,
                          reactorId: EntityId,
                          eventId: String) extends Message

trait PersistedEvent extends Serializable

case class IncomingRequest(requestMessage: RequestMessage) extends PersistedEvent

case class OutgoingResponse(responseMessage: ResponseMessage) extends PersistedEvent

case class OutgoingRequest(requestMessage: RequestMessage) extends PersistedEvent

case class IncomingResponse(responseMessage: ResponseMessage) extends PersistedEvent

case class IncomingEvent(eventMessage: EventMessage) extends PersistedEvent

case class OutgoingEvent(eventMessage: EventMessage) extends PersistedEvent

case class IncomingEventConfirmed(eventConfirmed: EventConfirmed) extends PersistedEvent

case class OutgoingEventConfirmed(eventConfirmed: EventConfirmed) extends PersistedEvent

case class IncomingEventProcessed(eventProcessed: EventProcessed) extends PersistedEvent


trait Reaction { val state: Entity }

case class RequestActor(reactorId: EntityId, request: Request, state: Entity) extends Reaction

case class ResponseToActor(response: Response, state: Entity) extends Reaction

case class NoReply(state: Entity) extends Reaction
