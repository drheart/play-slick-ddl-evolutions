package play.api.db.slick.ddl

import slick.driver.JdbcDriver
import slick.lifted.AbstractTable
import org.apache.xerces.dom3.as.ASModel
import slick.driver.JdbcProfile
import slick.lifted.Tag
import scala.reflect.internal.MissingRequirementError
import scala.reflect.runtime.universe
import scala.reflect.runtime.universe._
import slick.SlickException
import play.api.db.slick.DatabaseConfigProvider
import com.google.inject.Injector

class SlickDDLException(val message: String) extends Exception(message)

object TableScanner {
  lazy val logger = play.api.Logger(TableScanner.getClass)

  var classCache: List[String] = List()

  type DDL = slick.profile.SqlProfile#DDL

  private def subTypeOf(sym: Symbol, subTypeSymbol: Symbol) = {
    sym.typeSignature.baseClasses.find(_ == subTypeSymbol).isDefined
  }

  private def scanModulesAndFields(driver: slick.profile.SqlProfile, baseSym: ModuleSymbol, name: String)(implicit mirror: Mirror): Set[(Symbol, DDL)] = {
    println("scanModulesAndFields for: " + name)

    val baseInstance = mirror.reflectModule(baseSym).instance

    val allIds = ReflectionUtils.splitIdentifiers(name.replace(baseSym.fullName, ""))
    val isWildCard = allIds.lastOption.map(_ == "*").getOrElse(false)
    val ids = if (isWildCard) allIds.init else allIds

    val (outerSym, outerInstance) = ids.foldLeft((baseSym: Symbol)-> baseInstance) {
      case ((sym, instance), id) =>
        ReflectionUtils.reflectModuleOrField(id, instance, sym)
    }

    val tableQueryTypeSymbol = {
      import driver.simple._
      typeOf[TableQuery[_]].typeSymbol
    }

    val foundInstances: List[(Symbol, Any)] = if (subTypeOf(outerSym, tableQueryTypeSymbol) && !isWildCard) { //name was referencing a specific value
      println("scanModulesAndFields for: found table query instance (not wildcard): " + name)
      List(baseSym -> outerInstance)
    } else if (isWildCard) {
      //wildcard so we scan the instance we found for table queries
      val instancesNsyms = ReflectionUtils.scanModuleOrFieldByReflection(baseSym, outerSym, outerInstance)(subTypeOf(_, tableQueryTypeSymbol))
      if (instancesNsyms.isEmpty) println("w: Scanned object: '" + baseSym.fullName + "' for '" + name + "' but did not find any Slick Tables")
      println("scanModulesAndFields for: found " + instancesNsyms.size + " sub-instances (wildcard): " + name)
      instancesNsyms
    } else {
      throw new SlickDDLException("Found a matching object: '" + baseSym.fullName + "' for '" + name + "' but it is not a Slick Table and a wildcard was not specified")
    }

    foundInstances.map { case (name, instance) =>
      import driver.simple._
      name -> logSlickException(instance.asInstanceOf[TableQuery[Table[_]]].ddl)
    }.toSet
  }

  private def logSlickException[A](func: => A): A = {
    try {
      func
    } catch {
      case e: SlickException =>
        logger.error("Got an error converting to DDL. Check whether the profile used for the Table/TableQuery is the same as the one used by DDL generation.")
        throw e
    }
  }

  private def classToDDL(driver: slick.profile.SqlProfile, className: String, tableSymbol: Symbol, dbConfigProvider: DatabaseConfigProvider)(implicit mirror: Mirror): Option[(Symbol, DDL)] = {
    def construct(className: String, depth: Int): Any = {
      try {
        if (className == "play.api.db.slick.DatabaseConfigProvider") {
          dbConfigProvider
        } else {
          classCache = className :: classCache
          println("classToDDL for: " + className)
          val classSymbol = mirror.staticClass(className)
          val constructorSymbol = classSymbol.typeSignature.decl(universe.termNames.CONSTRUCTOR)
          if (subTypeOf(classSymbol, tableSymbol) && constructorSymbol.isMethod) {
            println("classToDDL for: " + className + " is table and has constructor")
            val constructorMethod = constructorSymbol.asMethod
            val reflectedClass = mirror.reflectClass(classSymbol)
            val constructor = reflectedClass.reflectConstructor(constructorMethod)
            import driver.simple._
            logSlickException {
              Some(classSymbol -> TableQuery { tag =>
                val constructorParameters = constructorMethod.paramLists.flatMap { l => l.map(m => m.typeSignature.toString) }.dropRight(1).map(m => construct(m, depth + 1)) :+ tag
                val instance = constructor(constructorParameters:_*)
                instance.asInstanceOf[Table[_]]
              }.ddl)
            }
          } else if (constructorSymbol.isMethod && depth > 0) {
            println("classToDDL for: " + className + " is argument to table and has constructor")
            val constructorMethod = constructorSymbol.asMethod
            val reflectedClass = mirror.reflectClass(classSymbol)
            val constructor = reflectedClass.reflectConstructor(constructorMethod)
            val constructorParameters = constructorMethod.paramLists.flatMap { l => l.map(m => {
              println("classToDDL for: " + className + " has argument of type: " + m.typeSignature.toString)
              m.typeSignature.toString
            }) }.map(m => if (depth > 2) { println("Using null for existing class " + className); null } else { construct(m, depth + 1) })

            constructor(constructorParameters:_*)
          } else if (className == "play.api.db.slick.DatabaseConfigProvider") {
            println("classToDDL for: injecting instance of " + className)
            dbConfigProvider
          } else {
            null
          }
        }
      } catch {
        case e: IllegalArgumentException =>
          println("w: Found a Slick table: " + className + ", but it does not have a constructor without arguments. Cannot create DDL for this class")
          None
        case e: InstantiationException =>
          println("w: Could not initialize " + className + ". DDL Generation will be skipped.")
          null
        case e: MissingRequirementError =>
          println("i: MissingRequirementError for " + className + ". Probably means this is not a class. DDL Generation will be skipped.")
          null
        case e: ScalaReflectionException =>
          println("i: ScalaReflectionException for " + className + ". Probably means this is not a class. DDL Generation will be skipped.")
          null
        case e: AssertionError if e.getMessage.contains("not a type") =>
          println(s"i: Class $className couldn't be reflected into a Scala symbol. DDL Generation will be skipped: ${e.getMessage}")
          null
        case e: AssertionError if e.getMessage.contains("no symbol could be loaded") =>
          println(s"i: Class $className couldn't be reflected into a Scala symbol. DDL Generation will be skipped: ${e.getMessage}")
          null
        case e: Exception => 
          null
      }
    }

    construct(className, 0) match {
      case Some((sym: Symbol, ddl: DDL)) => Some((sym, ddl))
      case null => None
    }
  }

  val WildcardPattern = """(.*)\.\*""".r

  /**
   * Returns the names of objects/classes in a package
   */
  def scanPackage(name: String, classloader: ClassLoader): Set[String] = {
    import scala.collection.JavaConverters._
    ReflectionUtils.getReflections(classloader, name).map { reflections =>
      reflections.getStore //TODO: would be nicer if we did this using Scala reflection, alas staticPackage is non-deterministic:  https://issues.scala-lang.org/browse/SI-6573
        .get(classOf[org.reflections.scanners.TypesScanner])
        .keySet.asScala.toSet[String]
    }.toSet.flatten
  }

  /**
   * Reflect all DDL methods found for a set of names with wildcards used to scan for Slick Table classes, objects and packages
   */
  def reflectAllDDLMethods(names: Set[String], driver: slick.profile.SqlProfile, classloader: ClassLoader, dbConfigProvider: DatabaseConfigProvider): Set[DDL] = synchronized { //reflection API not thread-safe
    implicit val mirror = universe.runtimeMirror(classloader)

    val tableTypeSymbol = typeOf[AbstractTable[_]].typeSymbol

    val ddls = names.flatMap { name =>
      val maybeModule: Option[ModuleSymbol] = ReflectionUtils.findFirstModule(name)
      val currentDDLs = maybeModule match {
        case Some(moduleSymbol) =>
          println(name + " is a module: scanning... ")
          val instaniatedDDLs = scanModulesAndFields(driver, moduleSymbol, name)
          instaniatedDDLs
        case None =>
          println(name + " is not a module: checking if wildcard and converting classes...")
          val classDDLs = (name match {
            case WildcardPattern(packageName) => scanPackage(packageName, classloader)
            case name => Set(name)
          }).flatMap {
            classToDDL(driver, _, tableTypeSymbol, dbConfigProvider)
          }
          classDDLs
      }
      if (currentDDLs.isEmpty)
        logger.error("Could not find any classes or table queries for: " + name + "")
      currentDDLs
    }

    println(s"reflectAllDDLMethods(), will generate DDL for: ${ddls.toMap.keys.map(_.fullName).mkString(", ")}")

    ddls.groupBy(_._1.fullName).flatMap {
      case (name, ddls) =>
        if (ddls.size > 1) logger.warn(s"Found multiple ddls ${ddls.size} for: $name")
        ddls.headOption.map(_._2)
    }.toSet
  }

}
