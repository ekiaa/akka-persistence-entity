package com.github.ekiaa.akka.persistence.entity

import akka.actor.Props
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.HashMap

class PersistenceEntity(entityId: EntityId, system: PersistenceEntitySystem) extends PersistentActor with StrictLogging {

  import PersistenceEntity._

  logger.debug("PersistenceEntity[{}]: Created", entityId.persistenceId)

  var entity: Option[Entity] = None

  var inProcessing: Boolean = false

  var lastPersistedEvent: Option[PersistedEvent] = None

  var lastReaction: Option[Reaction] = None

  var lastOutgoingRequest: Option[OutgoingRequest] = None

  var lastIncomingRequest: Option[RequestMessage] = None

  var lastOutgoingResponses: Map[String, ResponseMessage] = HashMap.empty[String, ResponseMessage]

  override def persistenceId: String = entityId.persistenceId

  override def receiveRecover: Receive = {

    case SnapshotOffer(metadata, snapshot: Entity) =>
      entity = Some(system.recovery(snapshot))

    case incomingRequest: IncomingRequest =>
      lastPersistedEvent = Some(incomingRequest)
      lastIncomingRequest = Some(incomingRequest.requestMessage)
      val reaction = entity.getOrElse(system.build(entityId)).handleRequest(incomingRequest.requestMessage.request)
      lastReaction = Some(reaction)
      entity = Some(reaction.state)
      inProcessing = true

    case outgoingRequest: OutgoingRequest =>
      lastPersistedEvent = Some(outgoingRequest)
      lastReaction = None
      lastOutgoingRequest = Some(outgoingRequest)

    case incomingResponse: IncomingResponse =>
      lastPersistedEvent = Some(incomingResponse)
      lastOutgoingRequest = None
      val reaction = entity.getOrElse(system.build(entityId)).handleResponse(incomingResponse.responseMessage.response)
      lastReaction = Some(reaction)
      entity = Some(reaction.state)

    case outgoingResponse: OutgoingResponse =>
      lastPersistedEvent = Some(outgoingResponse)
      lastOutgoingResponses += (lastIncomingRequest.get.id -> outgoingResponse.responseMessage)
      lastIncomingRequest = None
      lastReaction = None
      inProcessing = false

    case RecoveryCompleted =>
      logger.debug("PersistenceEntity[{}]: Receive RecoveryCompleted", entityId.persistenceId)
      lastPersistedEvent match {
        case Some(incomingRequest: IncomingRequest) =>

        case Some(outgoingRequest: OutgoingRequest) =>

        case Some(incomingResponse: IncomingResponse) =>

        case Some(outgoingResponse: OutgoingResponse) =>

        case None =>
          entity match {
            case Some(_) =>

            case None =>
              entity = Some(system.build(entityId))
          }

        case _ =>
          throw new Exception(s"PersistenceEntity[$persistenceId]: Not matched lastPersistedEvent[$lastPersistedEvent]")
      }

  }

  override def receiveCommand: Receive = {

    case requestMessage: RequestMessage if !inProcessing =>
      handleIncomingRequest(requestMessage)

    case _: RequestMessage if inProcessing =>
      stash()

    case responseMessage: ResponseMessage if inProcessing =>
      handleIncomingResponse(responseMessage)

    case responseMessage: ResponseMessage =>
      logger.warn("PersistenceEntity[{}]: Receive responseMessage[{}] when not inProcessing", entityId.persistenceId, responseMessage)

    case VerifyStarted =>
      logger.debug("PersistenceEntity[{}]: Receive VerifyStarted message", entityId.persistenceId)
      sender() ! Started

    case Terminate =>
      logger.debug("PersistenceEntity[{}]: Receive Terminate message", entityId.persistenceId)
      context.stop(self)

    case unknown =>
      logger.warn(s"PersistenceEntity[{}]: Receive unknown message: [$unknown]", entityId.persistenceId)

  }

  private def handleIncomingRequest(requestMessage: RequestMessage) = {
    inProcessing = true
    persist(IncomingRequest(requestMessage)) {
      incomingRequest =>
        require(entity.isDefined, s"PersistenceEntity[$persistenceId]: entity should be defined when invoked handleIncomingRequest with requestMessage[$requestMessage]")
        lastIncomingRequest = Some(incomingRequest.requestMessage)
        val reaction = entity.get.handleRequest(incomingRequest.requestMessage.request)
        handleReaction(reaction)
    }
  }

  private def handleIncomingResponse(responseMessage: ResponseMessage) = {
    persist(IncomingResponse(responseMessage)) {
      incomingResponse =>
        require(entity.isDefined, s"PersistenceEntity[$persistenceId]: entity should be defined when invoked handleIncomingResponse with responseMessage[$responseMessage]")
        val reaction = entity.get.handleResponse(incomingResponse.responseMessage.response)
        handleReaction(reaction)
    }
  }

  private def handleReaction(reaction: Reaction): Unit = {

    reaction match {
      case action: RequestActor =>
        entity = Some(action.state)
        val requestMessage = RequestMessage(
          requesterId = entityId,
          reactorId = action.reactorId,
          request = action.request
        )
        handleOutgoingRequest(requestMessage)

      case action: ResponseToActor =>
        entity = Some(action.state)
        val requesterId = lastIncomingRequest.get.requesterId
        val correlationId = lastIncomingRequest.get.correlationId
        val responseMessage = ResponseMessage(
          correlationId = correlationId,
          requesterId = requesterId,
          reactorId = entityId,
          response = action.response
        )
        handleOutgoingResponse(responseMessage)

      case action: Ignore =>
        entity = Some(action.state)

    }
  }

  private def handleOutgoingRequest(requestMessage: RequestMessage) = {
    persist(OutgoingRequest(requestMessage)) {
      outgoingRequest =>
        system.sendMessage(outgoingRequest.requestMessage)
    }
  }

  private def handleOutgoingResponse(responseMessage: ResponseMessage) = {
    persist(OutgoingResponse(responseMessage)) {
      outgoingResponse =>
        system.sendMessage(outgoingResponse.responseMessage)
        inProcessing = false
        unstashAll()
    }
  }

}

object PersistenceEntity {

  case object Terminate

  case object VerifyStarted

  case object Started

  def props(entityId: EntityId, system: PersistenceEntitySystem): Props =
    Props(classOf[PersistenceEntity], entityId, system)

}