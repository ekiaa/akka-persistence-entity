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

  override def preStart(): Unit = {
    logger.debug("PersistenceEntity[{}]: Started", entityId.persistenceId)
    super.preStart()
  }

  override def receiveRecover: Receive = {

    case SnapshotOffer(metadata, snapshot: Entity) =>
      logger.trace("PersistenceEntity[{}]: Recovered snapshot[{}]", entityId.persistenceId, snapshot)
      entity = Some(system.recovery(snapshot))

    case incomingRequest: IncomingRequest =>
      logger.trace("PersistenceEntity[{}]: Recovered incomingRequest[{}]", entityId.persistenceId, incomingRequest)
      inProcessing = true
      val reaction = entity.getOrElse(system.build(entityId)).handleRequest(incomingRequest.requestMessage.request)
      entity = Some(reaction.state)
      lastPersistedEvent = Some(incomingRequest)
      lastIncomingRequest = Some(incomingRequest.requestMessage)
      lastReaction = Some(reaction)

    case outgoingRequest: OutgoingRequest =>
      logger.trace("PersistenceEntity[{}]: Recovered outgoingRequest[{}]", entityId.persistenceId, outgoingRequest)
      lastPersistedEvent = Some(outgoingRequest)
      lastOutgoingRequest = Some(outgoingRequest)
      lastReaction = None

    case incomingResponse: IncomingResponse =>
      logger.trace("PersistenceEntity[{}]: Recovered incomingResponse[{}]", entityId.persistenceId, incomingResponse)
      require(entity.isDefined, s"PersistenceEntity[$persistenceId]: entity should be defined when recovered incomingResponse[$incomingResponse]")
      val reaction = entity.get.handleResponse(incomingResponse.responseMessage.response)
      entity = Some(reaction.state)
      lastPersistedEvent = Some(incomingResponse)
      lastOutgoingRequest = None
      lastReaction = Some(reaction)

    case outgoingResponse: OutgoingResponse =>
      logger.trace("PersistenceEntity[{}]: Recovered outgoingResponse[{}]", entityId.persistenceId, outgoingResponse)
      inProcessing = false
      lastOutgoingResponses += (lastIncomingRequest.get.id -> outgoingResponse.responseMessage)
      lastPersistedEvent = Some(outgoingResponse)
      lastIncomingRequest = None
      lastReaction = None

    case RecoveryCompleted =>
      logger.debug("PersistenceEntity[{}]: Receive RecoveryCompleted", entityId.persistenceId)
      lastPersistedEvent match {
        case Some(incomingRequest: IncomingRequest) =>
          logger.trace("PersistenceEntity[{}]: Complete recovering with last event incomingRequest[{}]", entityId.persistenceId, incomingRequest)
          require(lastReaction.isDefined, s"PersistenceEntity[$persistenceId]: lastReaction should be defined when trying replay reaction on incomingRequest[$incomingRequest]")
          handleReaction(lastReaction.get)

        case Some(outgoingRequest: OutgoingRequest) =>
          logger.trace("PersistenceEntity[{}]: Complete recovering with last event outgoingRequest[{}]", entityId.persistenceId, outgoingRequest)
          system.sendMessage(outgoingRequest.requestMessage)

        case Some(incomingResponse: IncomingResponse) =>
          logger.trace("PersistenceEntity[{}]: Complete recovering with last event incomingResponse[{}]", entityId.persistenceId, incomingResponse)
          require(lastReaction.isDefined, s"PersistenceEntity[$persistenceId]: lastReaction should be defined when trying replay reaction on incomingResponse[$incomingResponse]")
          handleReaction(lastReaction.get)

        case Some(outgoingResponse: OutgoingResponse) =>
          logger.trace("PersistenceEntity[{}]: Complete recovering with last event outgoingResponse[{}]", entityId.persistenceId, outgoingResponse)

        case None =>
          logger.trace("PersistenceEntity[{}]: Complete recovering without any event", entityId.persistenceId)
          entity match {
            case Some(e) =>
              logger.trace("PersistenceEntity[{}]: Complete recovering with entity[{}]", entityId.persistenceId, e)
            case None =>
              logger.trace("PersistenceEntity[{}]: Complete recovering without entity", entityId.persistenceId)
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
      logger.trace("PersistenceEntity[{}]: Receive VerifyStarted message", entityId.persistenceId)
      sender() ! Started

    case Terminate =>
      logger.trace("PersistenceEntity[{}]: Receive Terminate message", entityId.persistenceId)
      context.stop(self)

    case unknown =>
      logger.warn(s"PersistenceEntity[{}]: Receive unknown message: [$unknown]", entityId.persistenceId)

  }

  override def postStop(): Unit = {
    logger.debug("PersistenceEntity[{}]: Stopped", entityId.persistenceId)
    super.postStop()
  }

  private def handleIncomingRequest(requestMessage: RequestMessage) = {
    logger.trace("PersistenceEntity[{}]: Invoked handleIncomingRequest with requestMessage[{}]", entityId.persistenceId, requestMessage)
    inProcessing = true
    persist(IncomingRequest(requestMessage)) {
      incomingRequest =>
        logger.trace("PersistenceEntity[{}]: Persisted incomingRequest[{}]", entityId.persistenceId, incomingRequest)
        require(entity.isDefined, s"PersistenceEntity[$persistenceId]: entity should be defined when invoked handleIncomingRequest with requestMessage[$requestMessage]")
        lastIncomingRequest = Some(incomingRequest.requestMessage)
        val reaction = entity.get.handleRequest(incomingRequest.requestMessage.request)
        handleReaction(reaction)
    }
  }

  private def handleIncomingResponse(responseMessage: ResponseMessage) = {
    logger.trace("PersistenceEntity[{}]: Invoked handleIncomingResponse with responseMessage[{}]", entityId.persistenceId, responseMessage)
    persist(IncomingResponse(responseMessage)) {
      incomingResponse =>
        logger.trace("PersistenceEntity[{}]: Persisted incomingResponse[{}]", entityId.persistenceId, incomingResponse)
        require(entity.isDefined, s"PersistenceEntity[$persistenceId]: entity should be defined when invoked handleIncomingResponse with responseMessage[$responseMessage]")
        val reaction = entity.get.handleResponse(incomingResponse.responseMessage.response)
        handleReaction(reaction)
    }
  }

  private def handleReaction(reaction: Reaction): Unit = {
    logger.trace("PersistenceEntity[{}]: Invoked handleReaction with reaction[{}]", entityId.persistenceId, reaction)
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
    logger.trace("PersistenceEntity[{}]: Invoked handleOutgoingRequest with responseMessage[{}]", entityId.persistenceId, requestMessage)
    persist(OutgoingRequest(requestMessage)) {
      outgoingRequest =>
        logger.trace("PersistenceEntity[{}]: Persisted outgoingRequest[{}]", entityId.persistenceId, outgoingRequest)
        system.sendMessage(outgoingRequest.requestMessage)
    }
  }

  private def handleOutgoingResponse(responseMessage: ResponseMessage) = {
    logger.trace("PersistenceEntity[{}]: Invoked handleOutgoingResponse with responseMessage[{}]", entityId.persistenceId, responseMessage)
    persist(OutgoingResponse(responseMessage)) {
      outgoingResponse =>
        logger.trace("PersistenceEntity[{}]: Persisted outgoingResponse[{}]", entityId.persistenceId, outgoingResponse)
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