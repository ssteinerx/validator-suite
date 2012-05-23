//package org.w3.vs.store
//
//import org.w3.vs.model._
//import org.w3.banana._
//import org.w3.banana.diesel._
//import org.w3.vs._
//
///**
// * creates [EntityGraphBinder]s for the VS entities
// */
//case class Binders[Rdf <: RDF](
//  ops: RDFOperations[Rdf],
//  union: GraphUnion[Rdf],
//  graphTraversal: RDFGraphTraversal[Rdf]) {
//
//  import ops._
//  val diesel = Diesel(ops, union, graphTraversal)
//  import diesel._
//
//  object organization extends PrefixBuilder("organization", "https://validator.w3.org/suite/organization#", ops) {
//    val name = apply("name")
//    val Organization = apply("Organization")
//  }
//
//  val organizationUri = Prefix("", "https://validator.w3.org/suite/organization/", ops)
//
//  val OrganizationBinder = new EntityGraphBinder[Rdf, Organization] {
//
//    def fromGraph(uri: Rdf#IRI, graph: Rdf#Graph): Organization = {
//      val ng = GraphNode(uri, graph)
//      val organizationUri(id) = uri
//      val name = (ng / organization.name).asString getOrElse sys.error("")
//      Organization(OrganizationId.fromString(id), name)
//    }
//
//    def toGraph(t: Organization): Rdf#Graph = (
//      toUri(t).a(organization.Organization)
//        -- organization.name ->- t.name
//    ).graph
//
//    def toUri(t: Organization): Rdf#IRI = organizationUri(t.id.toString)
//
//  }
//
//}
//
//case class Stores[Rdf <: RDF](
//  store: RDFStore[Rdf],
//  binders: Binders[Rdf]) {
//
//  import binders._
//
//  val OrganizationStore = EntityStore(store, OrganizationBinder)
//
//}
