// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool.metrics

import java.time.Clock

import com.daml.ledger.api.benchtool.util.TimeUtil
import com.daml.metrics.api.MetricHandle.{Counter, Gauge, Histogram, MetricsFactory}
import com.daml.metrics.api.{MetricName, MetricsContext}
import com.google.protobuf.timestamp.Timestamp

final class ExposedMetrics[T](
    counterMetric: ExposedMetrics.CounterMetric[T],
    bytesProcessedMetric: ExposedMetrics.BytesProcessedMetric[T],
    delayMetric: Option[ExposedMetrics.DelayMetric[T]],
    latestRecordTimeMetric: Option[ExposedMetrics.LatestRecordTimeMetric[T]],
    clock: Clock,
) {
  def onNext(elem: T): Unit = {
    counterMetric.counter.inc(counterMetric.countingFunction(elem))(MetricsContext.Empty)
    bytesProcessedMetric.bytesProcessed.inc(bytesProcessedMetric.sizingFunction(elem))(
      MetricsContext.Empty
    )
    delayMetric.foreach { metric =>
      val now = clock.instant()
      metric
        .recordTimeFunction(elem)
        .foreach { recordTime =>
          val delay = TimeUtil.durationBetween(recordTime, now)
          metric.delays.update(delay.getSeconds)
        }
    }
    latestRecordTimeMetric.foreach { metric =>
      metric
        .recordTimeFunction(elem)
        .lastOption
        .foreach(recordTime => metric.latestRecordTime.updateValue(recordTime.seconds))
    }
  }

}

object ExposedMetrics {
  private val Prefix: MetricName = MetricName.Daml :+ "bench_tool"

  case class CounterMetric[T](counter: Counter, countingFunction: T => Long)
  case class BytesProcessedMetric[T](bytesProcessed: Counter, sizingFunction: T => Long)
  case class DelayMetric[T](delays: Histogram, recordTimeFunction: T => Seq[Timestamp])
  case class LatestRecordTimeMetric[T](
      latestRecordTime: Gauge[Long],
      recordTimeFunction: T => Seq[Timestamp],
  )

  def apply[T](
      streamName: String,
      factory: MetricsFactory,
      countingFunction: T => Long,
      sizingFunction: T => Long,
      recordTimeFunction: Option[T => Seq[Timestamp]],
      clock: Clock = Clock.systemUTC(),
  ): ExposedMetrics[T] = {
    val counterMetric = CounterMetric[T](
      counter = factory.counter(
        Prefix :+ "count" :+ streamName
      ),
      countingFunction = countingFunction,
    )
    val bytesProcessedMetric = BytesProcessedMetric[T](
      bytesProcessed = factory.counter(
        Prefix :+ "bytes_read" :+ streamName
      ),
      sizingFunction = sizingFunction,
    )
    val delayMetric = recordTimeFunction.map { f =>
      DelayMetric[T](
        delays = factory.histogram(
          Prefix :+ "delay" :+ streamName
        ),
        recordTimeFunction = f,
      )
    }
    val latestRecordTimeMetric = recordTimeFunction.map { f =>
      LatestRecordTimeMetric[T](
        latestRecordTime = factory.gauge(
          Prefix :+ "latest_record_time" :+ streamName,
          0L,
        )(MetricsContext.Empty),
        recordTimeFunction = f,
      )
    }

    new ExposedMetrics[T](
      counterMetric = counterMetric,
      bytesProcessedMetric = bytesProcessedMetric,
      delayMetric = delayMetric,
      latestRecordTimeMetric = latestRecordTimeMetric,
      clock = clock,
    )
  }
}
