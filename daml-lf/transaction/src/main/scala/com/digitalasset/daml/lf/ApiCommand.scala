// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package command

import com.daml.lf.data.Ref._
import com.daml.lf.value.Value
import com.daml.lf.data.{Bytes, ImmArray, Time}

/** Accepted commands coming from API */
sealed abstract class ApiCommand extends Product with Serializable {
  def typeId: TypeConName
}

object ApiCommand {

  /** Command for creating a contract
    *
    * @param templateId TypeConName of the template that the contract is instantiating
    * @param argument   value passed to the template
    */
  final case class Create(templateId: TypeConName, argument: Value) extends ApiCommand {
    override def typeId: TypeConName = templateId
  }

  /** Command for exercising a choice on an existing contract
    *
    * @param typeId templateId or interfaceId where the choice is defined
    * @param contractId contract on which the choice is exercised
    * @param choiceId   TypeConName choice
    * @param argument   value passed for the choice
    */
  final case class Exercise(
      typeId: TypeConName,
      contractId: Value.ContractId,
      choiceId: ChoiceName,
      argument: Value,
  ) extends ApiCommand

  /** Command for exercising a choice on an existing contract specified by its key
    *
    * @param templateId  TypeConName of the original contract
    * @param contractKey key of the contract on which the choice is exercised
    * @param choiceId    TypeConName choice
    * @param argument    value passed for the choice
    */
  final case class ExerciseByKey(
      templateId: TypeConName,
      contractKey: Value,
      choiceId: ChoiceName,
      argument: Value,
  ) extends ApiCommand {
    override def typeId: TypeConName = templateId
  }

  /** Command for creating a contract and exercising a choice
    * on that existing contract within the same transaction
    *
    * @param templateId     TypeConName of the original contract
    * @param createArgument value passed to the template
    * @param choiceId       TypeConName choice
    * @param choiceArgument value passed for the choice
    */
  final case class CreateAndExercise(
      templateId: TypeConName,
      createArgument: Value,
      choiceId: ChoiceName,
      choiceArgument: Value,
  ) extends ApiCommand {
    override def typeId: TypeConName = templateId
  }
}

/** Commands input adapted from ledger-api
  *
  * @param commands            a batch of commands to be interpreted/executed
  * @param ledgerEffectiveTime approximate time the commands to be effective,
  *                            interpretation will take this instant
  * @param commandsReference   id passed only for error reporting
  */
case class ApiCommands(
    commands: ImmArray[ApiCommand],
    ledgerEffectiveTime: Time.Timestamp,
    commandsReference: String,
)

/** An additional contract that is used to resolve contract id and contract key lookups during interpretation.
  *
  * @param   templateId   identifier of the template of disclosed contract
  * @param   contractId   the contract id of the disclosed contract
  * @param   argument     the payload of the disclosed contract
  * @param   metadata     metatdata attached to this disclosure. See [ContractMetadata].
  */
final case class DisclosedContract(
    templateId: Identifier,
    contractId: Value.ContractId,
    argument: Value,
    metadata: ContractMetadata,
)

/** Contract metadata attached to disclosed contracts.
  *
  * @param createdAt   ledger effective time of the transaction that created the contract
  * @param keyHash     hash of the contract key, if present
  * @param driverMetadata  opaque bytestring used by the underlying ledger implementation
  */
final case class ContractMetadata(
    createdAt: Time.Timestamp,
    keyHash: Option[crypto.Hash],
    driverMetadata: Bytes,
)
