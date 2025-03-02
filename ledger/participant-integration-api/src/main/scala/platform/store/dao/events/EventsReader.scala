// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao.events

import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.event_query_service.{
  GetEventsByContractIdResponse,
  GetEventsByContractKeyResponse,
}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.{Identifier, Party}
import com.daml.lf.value.Value
import com.daml.lf.value.Value.ContractId
import com.daml.logging.LoggingContext
import com.daml.metrics.Metrics
import com.daml.platform
import com.daml.platform.store.backend.{EventStorageBackend, ParameterStorageBackend}
import com.daml.platform.store.cache.LedgerEndCache
import com.daml.platform.store.cache.MutableCacheBackedContractStore.EventSequentialId
import com.daml.platform.store.dao.events.Raw.FlatEvent
import com.daml.platform.store.dao.{DbDispatcher, EventProjectionProperties, LedgerDaoEventsReader}

import scala.concurrent.{ExecutionContext, Future}

private[dao] sealed class EventsReader(
    val dbDispatcher: DbDispatcher,
    val eventStorageBackend: EventStorageBackend,
    val parameterStorageBackend: ParameterStorageBackend,
    val metrics: Metrics,
    val lfValueTranslation: LfValueTranslation,
    val ledgerEndCache: LedgerEndCache,
)(implicit ec: ExecutionContext)
    extends LedgerDaoEventsReader {

  protected val dbMetrics: metrics.daml.index.db.type = metrics.daml.index.db

  override def getEventsByContractId(contractId: ContractId, requestingParties: Set[Party])(implicit
      loggingContext: LoggingContext
  ): Future[GetEventsByContractIdResponse] = {

    val eventProjectionProperties = EventProjectionProperties(
      // Used by LfEngineToApi
      verbose = true,
      // Needed to get create arguments mapped
      witnessTemplateIdFilter = requestingParties.map(_ -> Set.empty[Identifier]).toMap,
      // We do not need interfaces mapped
      witnessInterfaceViewFilter = Map.empty,
    )

    for {
      rawEvents <- dbDispatcher.executeSql(dbMetrics.getEventsByContractId)(
        eventStorageBackend.eventReaderQueries.fetchContractIdEvents(
          contractId,
          requestingParties = requestingParties,
          endEventSequentialId = ledgerEndCache()._2,
        )
      )

      deserialized <- Future.traverse(rawEvents) {
        _.event.applyDeserialization(lfValueTranslation, eventProjectionProperties)
      }

      createEvent = deserialized.flatMap(_.event.created).headOption
      archiveEvent = deserialized.flatMap(_.event.archived).headOption

    } yield {
      if (createEvent.exists(stakeholders(_).exists(requestingParties.map(identity[String])))) {
        GetEventsByContractIdResponse(createEvent, archiveEvent)
      } else {
        GetEventsByContractIdResponse(None, None)
      }
    }
  }

  private def stakeholders(e: CreatedEvent): Set[String] = e.signatories.toSet ++ e.observers

  override def getEventsByContractKey(
      contractKey: Value,
      templateId: Ref.Identifier,
      requestingParties: Set[Party],
      endExclusiveSeqId: Option[EventSequentialId],
      maxIterations: Int,
  )(implicit loggingContext: LoggingContext): Future[GetEventsByContractKeyResponse] = {
    val keyHash: String = platform.Key.assertBuild(templateId, contractKey).hash.bytes.toHexString

    val eventProjectionProperties = EventProjectionProperties(
      // Used by LfEngineToApi
      verbose = true,
      // Needed to get create arguments mapped
      witnessTemplateIdFilter = requestingParties.map(_ -> Set.empty[Identifier]).toMap,
      // We do not need interfaces mapped
      witnessInterfaceViewFilter = Map.empty,
    )

    for {

      (
        rawCreate: Option[FlatEvent.Created],
        rawArchive: Option[FlatEvent.Archived],
        eventSequentialId,
      ) <- dbDispatcher
        .executeSql(dbMetrics.getEventsByContractKey) { conn =>
          eventStorageBackend.eventReaderQueries.fetchNextKeyEvents(
            keyHash,
            requestingParties,
            endExclusiveSeqId.getOrElse(ledgerEndCache()._2 + 1),
            maxIterations,
          )(conn)
        }

      createEvent <- rawCreate.fold(Future[Option[CreatedEvent]](None)) { e =>
        e.deserializeCreateEvent(lfValueTranslation, eventProjectionProperties).map(Some(_))
      }
      archiveEvent = rawArchive.map(_.deserializedArchivedEvent())

      continuationToken = eventSequentialId
        .map(_.toString)
        .getOrElse(GetEventsByContractKeyResponse.defaultInstance.continuationToken)

    } yield {
      GetEventsByContractKeyResponse(createEvent, archiveEvent, continuationToken)
    }
  }

}
