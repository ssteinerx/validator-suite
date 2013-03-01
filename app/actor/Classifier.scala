package org.w3.vs.actor

import org.w3.vs.model._

object Classifier {

  val all: List[Classifier[_]] = List(
    SubscribeToRunEvent,
    SubscribeToJobData,
    SubscribeToResourceData,
    SubscribeToAssertion,
    SubscribeToGroupedAssertionData
  )

  implicit case object SubscribeToRunEvent extends Classifier[RunEvent]
  implicit case object SubscribeToJobData extends Classifier[JobData]
  implicit case object SubscribeToResourceData extends Classifier[ResourceData]
  implicit case object SubscribeToAssertion extends Classifier[Assertion]
  implicit case object SubscribeToGroupedAssertionData extends Classifier[GroupedAssertionData]

}

sealed trait Classifier[Event]
