// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.codegen.backend.java.inner

import com.daml.ledger.javaapi
import com.daml.lf.data.Ref.PackageId
import com.daml.lf.typesig.Record
import com.squareup.javapoet.{ClassName, ParameterizedTypeName, TypeSpec, TypeVariableName}
import com.typesafe.scalalogging.StrictLogging

import javax.lang.model.element.Modifier
import scala.jdk.CollectionConverters._

private[inner] object RecordClass extends StrictLogging {

  def generate(
      packageId: PackageId,
      className: ClassName,
      typeParameters: IndexedSeq[String],
      record: Record.FWT,
  )(implicit
      packagePrefixes: PackagePrefixes
  ): TypeSpec = {
    TrackLineage.of("record", className.simpleName()) {
      logger.info("Start")
      val fields = getFieldsWithTypes(record.fields)
      val recordType = TypeSpec
        .classBuilder(className)
        .addModifiers(Modifier.PUBLIC)
        .superclass(
          if (typeParameters.isEmpty)
            ParameterizedTypeName.get(
              ClassName get classOf[javaapi.data.codegen.DamlRecord[_]],
              className,
            )
          else ClassName get classOf[Object]
        )
        .addTypeVariables(typeParameters.map(TypeVariableName.get).asJava)
        .addFields(RecordFields(fields).asJava)
        .addField(createPackageIdField(packageId))
        .addMethods(RecordMethods(fields, className, typeParameters).asJava)
        .build()
      logger.debug("End")
      recordType
    }
  }
}
