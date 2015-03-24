package reactive.recommendations.server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.{TimeUnit, Executors}

import com.sksamuel.elastic4s.mappings.FieldType.{LongType, DateType, StringType}
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.update.{UpdateResponse, UpdateRequest}
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.sort.SortOrder
import org.slf4j.LoggerFactory
import reactive.recommendations.commons.domain.Action
import reactive.recommendations.commons.domain.{User, Action, ContentItem}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.ElasticClient
import collection.JavaConversions._
import scala.concurrent.duration.Duration

import scala.concurrent.{ExecutionContext, Future}


/**
 * Created by denik on 30.11.2014.
 */
object ElasticServices {

  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  //    val client = ElasticClient.remote("predictio8.hcl.pp.coursmos.com" -> 9300)
  val clusterSettings = ImmutableSettings.settingsBuilder().put("cluster.name", "xyz").build()
  val client = ElasticClient.remote(clusterSettings, "localhost" -> 9300)
  val defaultLimit = 100
  val defaultInternalLimit = 1000

  val log = LoggerFactory.getLogger(ServerRunner.getClass)

  val incrementTemplate =
    """
      |if(!ctx._source.containsKey('%1$s')){
      | ctx._source.%1$s = %2$s
      |} else {
      | ctx._source.%1$s += %2$s
      |}
    """.stripMargin

  val putTemplate =
    """
      |if(!ctx._source.containsKey('%1$s')){
      | ctx._source.%1$s = %2$s
      |}
    """.stripMargin


  def createProfileIndex(): Future[CreateIndexResponse] = client.execute {
    create index "profiles" shards 5 replicas 1 mappings(
      "user" as(
        "id" typed StringType,
        "sex" typed StringType,
        "birthDate" typed DateType,
        "geo" typed StringType,
        "categories" typed StringType,
        "l10n" typed StringType
        ),

      "intervalInterest" as(
        "id" typed StringType,
        "user" typed StringType,
        "interval" typed StringType,
        "sex" typed StringType,
        "geo" typed StringType,
        "age" typed StringType,
        "l10n" typed StringType,
        "actionsCount" typed LongType
        )

      )
  }


  def createItemIndex(): Future[CreateIndexResponse] = client.execute {
    create index "items" shards 5 replicas 1 mappings (
      "item" as(
        "id" typed StringType,
        "version" typed DateType,
        "author" typed StringType,
        "tags" typed StringType,
        "terms" typed StringType,
        "categories" typed StringType,
        "l10n" typed StringType
        )
      )
  }

  def createActionIndex(): Future[CreateIndexResponse] = client.execute {
    create index "actions" shards 7 replicas 1 mappings {
      "action" as(
        "id" typed StringType,
        "item" typed StringType,
        "user" typed StringType,
        "type" typed StringType,
        "ts" typed DateType
        )
    }
  }


  def indexActionLogic(action: Action): Future[Boolean] = {

    val itemFuture = findItem(action.item)
    val userFuture = findUser(action.user)

    val iuf = for {
      item <- itemFuture
      user <- userFuture
    } yield (item, user)

    iuf.map {
      iuo =>

        val itemUserOption = for {
          i <- iuo._1
          u <- iuo._2
        } yield (i, u)

        itemUserOption.map {
          itemUser =>

            //TODO
            // find content item
            // index user monthly interest yyyy-MM->axes data + counts
            // index tops
            // index monthly interest

            indexAction(action)
            true
        }.getOrElse(false)

    }

  }


  def indexAction(action: Action): Future[IndexResponse] = client.execute {
    val docFields = collection.mutable.Map[String, Any]()
    docFields += ("id" -> action.id)
    docFields += ("item" -> action.item)
    docFields += ("user" -> action.user)
    docFields += ("type" -> action.actionType)
    docFields += ("ts" -> action.ts)
    index into "actions/action" id (action.id) fields (docFields)
  }

  def indexMonthlyInterest(action: Action, user: User, item: ContentItem): Future[BulkResponse] = {
    val sdf = new SimpleDateFormat("yyyy-MM")
    val docFields = collection.mutable.Map[String, Any]()
    val docId = "%1$s_%2$s".format(user.id, sdf.format(action.tsAsDate().getOrElse(new Date())))

    docFields += ("id" -> docId)

    user.age().map {
      v =>
        docFields += ("age" -> v)
    }
    user.categories.map {
      v =>
        docFields += ("categories" -> v.toArray)
    }
    user.geo.map {
      v =>
        docFields += ("geo" -> v)
    }
    user.sex.map {
      v =>
        docFields += ("sex" -> v)
    }

    val deltaScript = new StringBuilder()
    val deltaParams = collection.mutable.Map[String, Any]()

    appendToScript(deltaScript, deltaParams, Some(docFields), false, "actionsCount", "ac", 1)

    item.categories.foreach {
      catOption =>
        catOption.foreach {
          cat =>
            appendToScript(deltaScript, deltaParams, Some(docFields), false, "c_%1$s".format(cat), "p_c_%1$s".format(cat), 1)
        }
    }

    item.tags.foreach {
      catOption =>
        catOption.foreach {
          cat =>
            appendToScript(deltaScript, deltaParams, Some(docFields), false, "tg_%1$s".format(cat), "p_tg_%1$s".format(cat), 1)
        }
    }

    item.terms.foreach {
      catOption =>
        catOption.foreach {
          cat =>
            appendToScript(deltaScript, deltaParams, Some(docFields), false, "t_%1$s".format(cat), "p_t_%1$s".format(cat), 1)
        }
    }


    log.info("" + deltaScript.toString())
    log.info("" + deltaParams)

    client.execute {
      bulk(
        update(docId) in ("profiles/intervalInterest") script (deltaScript.toString()) params (deltaParams.toMap) upsert (docFields)
      )
    }
  }


  def appendToScript(script: StringBuilder, params: collection.mutable.Map[String, Any], upsertParams: Option[collection.mutable.Map[String, Any]], isPut: Boolean, property: String, pn: String, value: Any): Unit = {
    script.append(
      (if (isPut) {
        putTemplate
      } else {
        incrementTemplate
      }).format(property, pn))
    params += (pn -> value)
    upsertParams.map {
      p =>
        p += (property -> value)
    }
  }

  def indexItem(item: ContentItem): Future[IndexResponse] = client.execute {
    val docFields = collection.mutable.Map[String, Any]()
    docFields += ("id" -> item.id)
    item.author.map {
      v =>
        docFields += ("author" -> v)
    }
    item.categories.map {
      v =>
        docFields += ("categories" -> v.toArray)
    }
    item.tags.map {
      v =>
        docFields += ("tags" -> v.toArray)
    }
    item.terms.map {
      v =>
        docFields += ("terms" -> v.toArray)
    }
    item.createdTs.map {
      v =>
        docFields += ("version" -> v)
    }
    item.l10n.map {
      v =>
        docFields += ("l10n" -> v)
    }
    index into "items/item" id (item.id) fields (docFields)
  }


  def deleteIndexes(): Unit = {
    log.info("" + client.execute {
      delete index "items"
    }.await)
    log.info("" + client.execute {
      delete index "profiles"
    }.await)
    log.info("" + client.execute {
      delete index "actions"
    }.await)
  }

  def createIndexes(): Unit = {
    log.info("" + createProfileIndex().await)
    log.info("" + createItemIndex().await)
    log.info("" + createActionIndex().await)
  }


  def indexUser(user: User): Future[IndexResponse] = client.execute {
    val docFields = collection.mutable.Map[String, Any]()
    docFields += ("id" -> user.id)
    user.birthDate.map {
      v =>
        docFields += ("birthDate" -> v)
    }
    user.categories.map {
      v =>
        docFields += ("categories" -> v.toArray)
    }
    user.geo.map {
      v =>
        docFields += ("geo" -> v)
    }
    user.sex.map {
      v =>
        docFields += ("sex" -> v)
    }
    log.info("" + docFields)
    index into "profiles/user" id (user.id) fields (docFields)
  }


  def findItem(itemId: String): Future[Option[ContentItem]] = {
    client.execute {
      search in "items" types "item" fields("terms", "categories", "tags", "author") query {
        ids(itemId)
      } limit (1)
    }.map {
      sr =>
        if (sr.getHits.hits().isEmpty) {
          None
        } else {
          val hits = sr.getHits.hits()(0)
          Some(ContentItem(itemId, Some("ts"), extractValues(hits, "tags"), extractValues(hits, "categories"), extractValues(hits, "terms"), extractValue(hits, "author"), extractValues(hits, "l10n")))
        }
    }
  }

  def findUser(userId: String): Future[Option[User]] = {
    client.execute {
      search in "profiles" types "user" fields("sex", "birthDate", "geo", "categories", "l10n") query {
        ids(userId)
      } limit (1)
    }.map {
      sr =>
        if (sr.getHits.hits().isEmpty) {
          None
        } else {
          val hits = sr.getHits.hits()(0)
          Some(User(userId, extractValue(hits, "sex"), extractValue(hits, "birthDate"), extractValue(hits, "geo"), extractValues(hits, "l10n")))
        }
    }
  }

  def extractValues(sh: SearchHit, fn: String): Option[Set[String]] = {
    if (sh.fields().containsKey(fn)) {
      Some(sh.fields().get(fn).values().map("" + _).toSet)
    } else {
      None
    }
  }

  def extractValue(sh: SearchHit, fn: String): Option[String] = {
    if (sh.fields().containsKey(fn)) {
      Some(sh.fields().get(fn).value[String]())
    } else {
      None
    }
  }


  def findItemIds(userId: String): Future[Set[String]] = {
    client.execute {
      search in "actions" types "action" fields ("item") query {
        "*:*"
      } filter {
        termFilter("user", userId)
      } sort {
        by field ("ts") order SortOrder.DESC
      } limit (defaultInternalLimit)
    }.map {
      sr =>
        sr.getHits.hits().map {
          hit =>
            val itemValue = hit.field("item")
            val item = if (itemValue != null) {
              itemValue.getValue[String]
            } else {
              ""
            }
            item
        }.filter(!_.isEmpty).toSet
    }
  }

  def findCategoriesTags(userId: String): Future[UserPreferences] = {
    findItemIds(userId).flatMap {
      ids =>

        log.info("ids=" + ids)


        client.execute {
          search in "items" types "item" fields("tags", "categories") query {
            "*:*"
          } filter {
            idsFilter(ids.toArray: _*)
          } sort {
            by field ("createdTs") order SortOrder.DESC
          } limit (defaultInternalLimit)
        }.map {
          sr =>
            val tc = sr.getHits.hits().map {
              hit =>
                val categoriesValue = hit.field("categories")
                val cats: Array[String] = if (categoriesValue != null) {
                  categoriesValue.getValues.map(_.toString).toArray
                } else {
                  Array[String]()
                }
                val tagsValue = hit.field("tags")
                val tags: Array[String] = if (tagsValue != null) {
                  tagsValue.getValues.map(_.toString).toArray
                } else {
                  Array[String]()
                }
                (cats, tags)
            }

            UserPreferences(tc.flatMap(_._1).toSet, tc.flatMap(_._2).toSet, ids)
        }
    }
  }


  def findItemsForUser(userId: String, limit: Option[Int] = None): Future[Array[String]] = {

    findCategoriesTags(userId).flatMap {
      up =>

        log.info("" + up)



        client.execute {

          if (up.ids.isEmpty) {
            search in "items" types "item" fields ("id") query {
              "*:*"
            } sort {
              by field ("createdTs") order SortOrder.DESC
            } limit (limit.getOrElse(defaultLimit))
          } else {
            search in "items" types "item" fields ("id") filter {
              not {
                idsFilter(up.ids.toArray: _*)
              }
            } query {
              bool {
                should {
                  termsQuery("tags", up.tags.toArray: _*).boost(100)
                  termsQuery("categories", up.categories.toArray: _*).boost(50)
                }
              }
            } sort {
              by field ("createdTs") order SortOrder.DESC
            } limit (limit.getOrElse(defaultLimit))
          }


        }.map {
          sr =>
            sr.getHits.hits().map {
              hit =>
                val fieldValue = hit.field("id")
                if (fieldValue != null) {
                  fieldValue.getValue
                } else {
                  ""
                }
            }.filter(!_.isEmpty)
        }
    }


  }


  def main(args: Array[String]) = {
    val sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSS")

    //    deleteIndexes()
    //    createIndexes()

    val u = User("u1", Some("male"), Some("1979-11-29"), Some("Minsk"), Some(Set[String]("cat1", "cat2")))
    val i = ContentItem("ci1", Some("2015-01-01T09:08:07.123"), Some(Set[String]("tag1", "tag2")), Some(Set[String]("cat1", "cat2")), Some(Set[String]("term1", "term2")), Some("author"), None)
    val a = Action("a1", sdf.format(new Date()), "u1", "ci1", "view")

    implicit val d = Duration(30, TimeUnit.SECONDS)

    //    log.info("" + indexUser(u).await)
    //    log.info("" + indexItem(i).await)
    // log.info("" + indexAction(Action("a1", sdf.format(new Date()), "u1", "ci1", "view")).await)
    log.info("" + indexMonthlyInterest(a, u, i).await.buildFailureMessage())


  }

  def main1(args: Array[String]) = {
    log.info("" + client.execute {
      search in "items" types "item" fields("categories", "tags") query {
        "*:*"
      } filter {
        idsFilter("i1", "i2")
      }
    }.await)
    log.info("" + findItemsForUser("u1").await.toBuffer)

  }

}


case class UserPreferences(categories: Set[String], tags: Set[String], ids: Set[String])