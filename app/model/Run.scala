package org.w3.vs.model

import org.w3.vs._
import org.w3.util._
import org.w3.vs.assertor._
import scalaz.Scalaz._
import scalaz._
import org.joda.time._
import org.w3.banana._

object Run {

  def diff(l: Set[URL], r: Set[URL]): Set[URL] = {
    val d1 = l -- r
    val d2 = r -- l
    d1 ++ d2
  }

  def getRunVO(id: RunId)(implicit conf: VSConfiguration): FutureVal[Exception, RunVO] = {
    import conf.binders._
    implicit val context = conf.webExecutionContext
    val uri = RunUri(id)
    FutureVal(conf.store.getNamedGraph(uri)) flatMap { graph => 
      FutureVal.pureVal[Throwable, RunVO]{
        val pointed = PointedGraph(uri, graph)
        RunVOBinder.fromPointedGraph(pointed)
      }(t => t)
    }
  }

  def get(id: RunId)(implicit conf: VSConfiguration): FutureVal[Exception, Run] = {
    for {
      vo <- getRunVO(id)
      job <- Job.get(vo.jobId)
    } yield {
      Run(
        id = vo.id,
        explorationMode = vo.explorationMode,
        createdAt = vo.createdAt,
        completedAt = vo.completedAt,
        timestamp = vo.timestamp,
        job = job,
        pending = Set.empty,
        resources = vo.resources,
        errors = vo.errors,
        warnings = vo.warnings,
        invalidated = 0,
        pendingAssertions = 0)
    }
  }

  def saveJobVO(vo: RunVO)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] = {
    import conf.binders._
    implicit val context = conf.webExecutionContext
    val graph = RunVOBinder.toPointedGraph(vo).graph
    val result = conf.store.addNamedGraph(RunUri(vo.id), graph)
    FutureVal(result)
  }

  def save(run: Run)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] =
    saveJobVO(run.toValueObject)
  
  def delete(run: Run)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] =
    sys.error("")

  def getRunVOs(jobId: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[RunVO]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders._
    import conf.diesel._
    val query = """
CONSTRUCT {
  ?runUri ?p ?o
} WHERE {
  graph ?g {
    ?runUri ont:jobId <#jobUri> .
    ?runUri ?p ?o
  }
}
""".replaceAll("#jobUri", JobUri(jobId).toString)
    val construct = SparqlOps.ConstructQuery(query, xsd, ont)
    FutureVal(store.executeConstruct(construct)) flatMapValidation { graph => fromGraphVO(conf)(graph) }
  }

  def fromGraphVO(conf: VSConfiguration)(graph: conf.Rdf#Graph): Validation[BananaException, Iterable[RunVO]] = {
    import conf.diesel._
    import conf.binders._
    val vos: Iterable[Validation[BananaException, RunVO]] =
      graph.getAllInstancesOf(ont.Run) map { pointed => RunVOBinder.fromPointedGraph(pointed) }
    vos.toList.sequence[({type l[X] = Validation[BananaException, X]})#l, RunVO]
  }

// given one job
//   latest RunVO: max createdAt
//   take all fields
// for this runId
//   take all the ResourceResponseVO
//   take all the AssertionVO
//   sort them
//   replay them on the Run


  def getLatestRun(jobId: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Option[Run]] = {
    implicit val context = conf.webExecutionContext
    Job.getLastCreated(jobId) flatMap {
      case None => FutureVal.successful[Exception, Option[Run]](None)
      case Some((runId, createdAt)) =>
        for {
          job <- Job.get(jobId)
          vo <- Run.getRunVO(runId)
          rrs <- ResourceResponse.getForRun(runId)
          assertions <- Assertion.getForRun(runId)
        } yield {
          var run = Run(
            id = vo.id,
            explorationMode = vo.explorationMode,
            createdAt = vo.createdAt,
            completedAt = vo.completedAt,
            timestamp = vo.timestamp,
            job = job)

          var receivedUrls: List[URL] = List.empty
          var extractedUrls: List[URL] = List.empty
          rrs.toList.sortBy(_.timestamp) foreach {
            case httpResponse: HttpResponse => {
              receivedUrls = httpResponse.url :: receivedUrls
              extractedUrls = httpResponse.extractedURLs ++ extractedUrls
            }
            case errorResponse: ErrorResponse => {
              receivedUrls = errorResponse.url :: receivedUrls   
            }
          }

          var errors = 0
          var warnings = 0
          assertions.toList.sortBy(_.timestamp) foreach { ass =>
            ass.severity match {
              case Error => errors += 1
              case Warning => warnings += 1
              case Info => ()
            }
          }

          // sort by timestamp first!
          // go through all the rrs to construct List[URL] respecting order of discovering
          //   accumulate url
          //   accumulate extractedURLs if HttpResponse
          // at this point, we know
          //   knownUrls: everything from accumulation (Set)
          //   fetched: accumulation minus received urls (Set)
          //   toBeExplored: accumulation minus duplicates minus fetched urls
          // go through assertions
          //   update run on the way
          //   build a List[AssertorCall]
          // the AssertorCall that were pending are:
          //   we got a rr for url u
          //   but there is no assertion received
          //   schedule these guys again (don't forget to maintain the counter of pending assertions)

          null
        }
    }
  }

}


/**
 * Run represents a coherent state of for an Run, modelized as an FSM
 * see http://akka.io/docs/akka/snapshot/scala/fsm.html
 */
case class Run(
    id: RunId = RunId(),
    explorationMode: ExplorationMode = ProActive,
    knownUrls: Set[URL] = Set.empty,
    toBeExplored: List[URL] = List.empty,
    fetched: Set[URL] = Set.empty,
    createdAt: DateTime,
    completedAt: Option[DateTime],
    timestamp: DateTime = DateTime.now(DateTimeZone.UTC),
    job: Job,
    pending: Set[URL] = Set.empty,
    resources: Int = 0,
    errors: Int = 0,
    warnings: Int = 0,
    invalidated: Int = 0,
    pendingAssertions: Int = 0)(implicit conf: VSConfiguration) {

  val logger = play.Logger.of(classOf[Run])

  def jobData: JobData = JobData(id, job.id, resources, errors, warnings, createdAt, completedAt)
  
  def getAssertions: FutureVal[Exception, Iterable[Assertion]] = Assertion.getForRun(this)

  def getAssertions(url: URL): FutureVal[Exception, Iterable[Assertion]] = Assertion.getForRun(this, url)
  
  def health: Int = jobData.health

  def strategy = job.strategy
  
  def save(): FutureVal[Exception, Unit] = Run.save(this)
  
  def delete(): FutureVal[Exception, Unit] = Run.delete(this)

  // Represent an article on the byUrl report
  // (resource url, last assertion timestamp, total warnings, total errors)
  def getURLArticles(): FutureVal[Exception, List[(URL, DateTime, Int, Int)]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders._
    import conf.diesel._
    val query = """
SELECT ?url
       (IF (BOUND(?w), ?w, 0) AS ?warnings)
       (IF (BOUND(?e), ?e, 0) AS ?errors)
       (IF (!BOUND(?a), ?b , IF (!BOUND(?b), ?a, IF (?a > ?b, ?a, ?b)) ) AS ?latest) {
  {
    SELECT ?url (COUNT(*) AS ?w) (MAX(?when) AS ?a) {
      graph ?g {
        ?assertion ont:runId <#runUri> ;
                   ont:severity "warning"^^xsd:string ;
                   ont:url ?url ;
                   ont:timestamp ?when .
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    } GROUP BY ?url
  }
  {
    SELECT ?url (COUNT(*) AS ?e) (MAX(?when) AS ?b) {
      graph ?g {
        ?assertion ont:runId <#runUri> ;
                   ont:severity "error"^^xsd:string ;
                   ont:url ?url ;
                   ont:timestamp ?when .
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    } GROUP BY ?url
  }
}
""".replaceAll("#runUri", RunUri(id).toString)
    import SparqlOps._
    val select = SelectQuery(query, xsd, ont)
    FutureVal(store.executeSelect(select)) flatMapValidation { rows =>
      val results = rows.toIterable map { row =>
        for {
          url <- row("url").flatMap(_.as[URL])
          warnings <- row("warnings").flatMap(_.as[Int])
          errors <- row("errors").flatMap(_.as[Int])
          latest <- row("latest").flatMap(_.as[DateTime])
        } yield {
          (url, latest, warnings, errors)
        }
      }
      val finalResults = results.toList.sequence[({type l[x] = Validation[BananaException, x]})#l, (URL, DateTime, Int, Int)]
      // the request is correct, so if a VarNotFound, it means that we had one empty row
      // need to see with ericP how to make it directly "no row"
      finalResults match {
        case Failure(VarNotFound(_)) => Success(List[(URL, DateTime, Int, Int)]())
        case _ => finalResults
      }
    }
  }

  def getURLArticle(url: URL): FutureVal[Exception, (URL, DateTime, Int, Int)] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders._
    import conf.diesel._
    val query = """
SELECT (IF(BOUND(?warnings), ?warnings, 0) AS ?warn)
       (IF(BOUND(?errors), ?errors, 0) AS ?err)
       (IF (!BOUND(?a), ?b , IF (!BOUND(?b), ?a, IF (?a > ?b, ?a, ?b)) ) AS ?latest) {
  {
    SELECT (COUNT(*) AS ?warnings) (MAX(?when) AS ?a) {
      graph ?g {
        ?assertion ont:runId <#runUri> ;
                   ont:severity "warning"^^xsd:string ;
                   ont:url "#url"^^xsd:anyURI ;
                   ont:timestamp ?when .
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    }
  }
  {
    SELECT (COUNT(*) AS ?errors) (MAX(?when) AS ?b) {
      graph ?g {
        ?assertion ont:runId <#runUri> ;
                   ont:severity "error"^^xsd:string ;
                   ont:url "#url"^^xsd:anyURI ;
                   ont:timestamp ?when .
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    }
  }
}
""".replaceAll("#runUri", RunUri(id).toString).replaceAll("#url", url.toString)
    import SparqlOps._
    val select = SelectQuery(query, xsd, ont)
    FutureVal(store.executeSelect(select)) flatMapValidation { rows =>
      // there is at least one answer because it's an aggregate
      // TODO see with ericP if we can have something more idiomatic
      val row = rows.toIterable.head
      //row.vars foreach { v => println(v + " -> " + row(v)) }
      val result =
        for {
          warnings <- row("warn").flatMap(_.as[Int])
          errors <- row("err").flatMap(_.as[Int])
          latest <- row("latest").flatMap(_.as[DateTime])
        } yield {
          (url, latest, warnings, errors)
        }
    result
    }
  }
  
  // Returns the assertors that validated @url, with their name and the total number of warnings and errors that they reported for @url.
  def getAssertorArticles(url: URL): FutureVal[Exception, List[(AssertorId, String, Int, Int)]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders._
    import conf.diesel._
    val query = """
SELECT DISTINCT ?assertor ?warnings ?errors WHERE {
  {
    SELECT ?assertor (SUM(IF(BOUND(?ctx), 1, 0)) AS ?warnings) {
      graph ?g {
        ?assertion a ont:Assertion ;
                   ont:runId <#runUri> ;
                   ont:severity "warning"^^xsd:string ;
                   ont:url "#url"^^xsd:anyURI ;
                   ont:assertorId ?assertor
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    } GROUP BY ?assertor
  }
  {
    SELECT ?assertor (SUM(IF(BOUND(?ctx), 1, 0)) AS ?errors) {
      graph ?g {
        ?assertion a ont:Assertion ;
                   ont:runId <#runUri> ;
                   ont:severity "error"^^xsd:string ;
                   ont:url "#url"^^xsd:anyURI ;
                   ont:assertorId ?assertor
      }
      OPTIONAL { graph ?ctx { ?ctx ont:assertionId ?assertion } }
    } GROUP BY ?assertor
  }
}
""".replaceAll("#runUri", RunUri(id).toString).replaceAll("#url", url.toString)
    import SparqlOps._
    val select = SelectQuery(query, xsd, ont)
    FutureVal(store.executeSelect(select)) flatMapValidation { rows =>
      val results = rows.toIterable map { row =>
        for {
          assertorId <- row("assertor").flatMap(_.as[AssertorId])
          warnings <- row("warnings").flatMap(_.as[Int])
          errors <- row("errors").flatMap(_.as[Int])
        } yield {
          val assertorName = Assertor.getName(assertorId)
          (assertorId, assertorName, warnings, errors)
        }
      }
      results.toList.sequence[({type l[x] = Validation[BananaException, x]})#l, (AssertorId, String, Int, Int)]
    }
  }

  /* methods related to the data */
  
  def toValueObject: RunVO = RunVO(id, job.id, explorationMode, createdAt, completedAt, timestamp, resources, errors, warnings)
  
  def numberOfKnownUrls: Int = knownUrls.count { _.authority === mainAuthority }

  /**
   * An exploration is over when there are no more urls to explore and no pending url
   */
  def noMoreUrlToExplore = pending.isEmpty && toBeExplored.isEmpty

  def isIdle = noMoreUrlToExplore && pendingAssertions == 0

  def isRunning = !isIdle

  def activity: RunActivity = if (isRunning) Running else Idle

  def state: (RunActivity, ExplorationMode) = (activity, explorationMode)

  private def shouldIgnore(url: URL): Boolean = {
    def notToBeFetched = IGNORE === strategy.getActionFor(url)
    def alreadyKnown = knownUrls contains url
    notToBeFetched || alreadyKnown
  }

  def numberOfRemainingAllowedFetches = strategy.maxResources - numberOfKnownUrls

  /**
   * Returns an Observation with the new urls to be explored
   */
  def withNewUrlsToBeExplored(urls: List[URL]): (Run, List[URL]) = {
    val filteredUrls = urls.filterNot{ url => shouldIgnore(url) }.distinct.take(numberOfRemainingAllowedFetches)
    val newData = this.copy(
      toBeExplored = toBeExplored ++ filteredUrls,
      knownUrls = knownUrls ++ filteredUrls)
    (newData, filteredUrls)
  }

  val mainAuthority: Authority = strategy.mainAuthority

  /**
   * A consolidated view of all the authorities for the pending urls
   */
  lazy val pendingAuthorities: Set[Authority] = pending map { _.authority }

  /**
   * Returns a couple Observation/Explore.
   *
   * The Explore  is the first one that could be fetched for the main authority.
   *
   * The returned Observation has set this Explore to be pending.
   */
  private def takeFromMainAuthority: Option[(Run, URL)] = {
    val optUrl = toBeExplored find { _.authority == mainAuthority }
    optUrl map { url =>
      (this.copy(
        pending = pending + url,
        toBeExplored = toBeExplored filterNot { _ == url }),
        url)
    }
  }

  /**
   * Returns (if possible, hence the Option) the first Explore that could
   * be fetched for any authority but the main one.
   * Also, this Explore must be the only one with this Authority.
   *
   * The returned Observation has set this Explore to be pending.
   */
  private def takeFromOtherAuthorities: Option[(Run, URL)] = {
    val pendingToConsiderer =
      toBeExplored.view filterNot { url => url.authority == mainAuthority || (pendingAuthorities contains url.authority) }
    pendingToConsiderer.headOption map { url =>
      (this.copy(
        pending = pending + url,
        toBeExplored = toBeExplored filterNot { _ == url }),
        url)
    }
  }

  lazy val mainAuthorityIsBeingFetched = pending exists { _.authority == mainAuthority }

  /**
   * Returns (if possible, hence the Option) the first Explore that could
   * be fetched, giving priority to the main authority.
   */
  def take: Option[(Run, URL)] = {
    val take = if (mainAuthorityIsBeingFetched) {
      takeFromOtherAuthorities
    } else {
      takeFromMainAuthority orElse takeFromOtherAuthorities
    }
    take
  }

  /**
   * Returns as many Explores as possible to be fetched.
   *
   * The returned Observation has all the Explores marked as being pending.
   */
  def takeAtMost(n: Int): (Run, List[URL]) = {
    var current: Run = this
    var urls: List[URL] = List.empty
    for {
      i <- 1 to (n - pending.size)
      (observation, url) <- current.take
    } {
      current = observation
      urls ::= url
    }
    (current, urls.reverse)
  }

  def withResourceResponse(response: ResourceResponse): Run = this.copy(
    pending = pending - response.url,
    fetched = fetched + response.url,
    resources = response match {
      case _: HttpResponse => resources + 1
      case _ => resources
    }
  )

  def withAssertorResponse(response: AssertorResponse): Run = response match {
    case result: AssertorResult =>
      this.copy(
        errors = errors + result.errors,
        warnings = warnings + result.warnings,
        pendingAssertions = pendingAssertions - 1) // lower bound is 0
    case fail: AssertorFailure => this.copy(pendingAssertions = pendingAssertions - 1) // TODO? should do something about that
  }

  def stopMe(): Run =
    this.copy(explorationMode = Lazy, toBeExplored = List.empty)

  def withMode(mode: ExplorationMode) = this.copy(explorationMode = mode)
    
}

