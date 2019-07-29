package es.upm.fi.dia.oeg.morph.r2rml.rdb.engine

import scala.collection.JavaConversions._
import es.upm.fi.dia.oeg.morph.base._
import java.util.Collection
import java.sql.ResultSet

import org.apache.jena.datatypes.xsd.XSDDatatype
import java.sql.ResultSetMetaData
import java.sql.Connection

import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTriplesMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLPredicateObjectMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTermMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLObjectMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLRefObjectMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTermMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument
import es.upm.fi.dia.oeg.morph.base.sql.MorphSQLConstant
import Zql.ZConstant
import es.upm.fi.dia.oeg.morph.base.sql.DatatypeMapper
import es.upm.fi.dia.oeg.morph.base.sql.MorphSQLUtility
import es.upm.fi.dia.oeg.morph.base.sql.IQuery
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLLogicalTable
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.AnonId
import org.apache.jena.vocabulary.RDF
import org.apache.jena.rdf.model.Literal
import es.upm.fi.dia.oeg.morph.base.materializer.MorphBaseMaterializer
import es.upm.fi.dia.oeg.morph.base.model.MorphBaseClassMapping
import es.upm.fi.dia.oeg.morph.base.model.MorphBaseMappingDocument
import es.upm.fi.dia.oeg.morph.r2rml.MorphR2RMLElementVisitor
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseUnfolder
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLSubjectMap
import org.apache.jena.rdf.model.Property
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLPredicateMap
import java.text.SimpleDateFormat
import java.text.DateFormat
import java.util.Locale

import org.apache.jena.graph.{Node, NodeFactory}
import org.slf4j.LoggerFactory

class MorphRDBDataTranslator(md:R2RMLMappingDocument, materializer:MorphBaseMaterializer
                             , unfolder:MorphRDBUnfolder, dataSourceReader:MorphRDBDataSourceReader
                             , connection:Connection, properties:MorphProperties)
  extends MorphBaseDataTranslator(md, materializer , unfolder, dataSourceReader
    , connection, properties)
    with MorphR2RMLElementVisitor {
  val dfInput = this.properties.inputDateFormat;
  //val dfOutput = this.properties.outputDateFormat;
  val xsdDateTimeURI = XSDDatatype.XSDdateTime.getURI().toString();
  val xsdBooleanURI = XSDDatatype.XSDboolean.getURI().toString();
  val xsdDurationURI = XSDDatatype.XSDduration.getURI().toString();
  val xsdDateURI = XSDDatatype.XSDdate.getURI().toString();
  val dbType = this.properties.databaseType;
  override val logger = LoggerFactory.getLogger(this.getClass());


  override def processCustomFunctionTransformationExpression(
                                                              argument:Object ) : Object = {
    null;
  }

  override def translateData(triplesMap:MorphBaseClassMapping) : Unit = {
    val query = this.unfolder.unfoldConceptMapping(triplesMap);
    this.generateRDFTriples(triplesMap, query);
    //		null;
  }

  override def translateData(triplesMaps:Iterable[MorphBaseClassMapping]) : Unit = {
    for(triplesMap <- triplesMaps) {
      try {
        this.visit(triplesMap.asInstanceOf[R2RMLTriplesMap]);
        //triplesMap.asInstanceOf[R2RMLTriplesMap].accept(this);
      } catch {
        case e:Exception => {
          logger.error("error while translating data of triplesMap : " + triplesMap);
          if(e.getMessage() != null) {
            logger.error("error message = " + e.getMessage());
          }

          //e.printStackTrace();
          throw new Exception(e.getMessage(), e);
        }
      }
    }
  }

  override def translateData(mappingDocument:MorphBaseMappingDocument ) = {
    val conn = this.connection

    val triplesMaps = mappingDocument.classMappings
    if(triplesMaps != null) {
      this.translateData(triplesMaps);
      //DBUtility.closeConnection(conn, "R2RMLDataTranslator");
    }
  }


  def visit( logicalTable:R2RMLLogicalTable) : Object = {
    // TODO Auto-generated method stub
    null;
  }

  override def visit(mappingDocument:R2RMLMappingDocument) : Object = {
    try {
      this.translateData(mappingDocument);
    } catch {
      case e:Exception => {
        e.printStackTrace();
        logger.error("error during data translation process : " + e.getMessage());
        throw new Exception(e.getMessage());
      }
    }

    null;
  }

  def visit(objectMap:R2RMLObjectMap ) : Object = {
    // TODO Auto-generated method stub
    null;
  }

  def visit(refObjectMap:R2RMLRefObjectMap ) : Object  = {
    // TODO Auto-generated method stub
    null;
  }

  def visit(r2rmlTermMap:R2RMLTermMap) : Object = {
    // TODO Auto-generated method stub
    null;
  }



  def generateRDFTriples(logicalTable:R2RMLLogicalTable ,  sm:R2RMLSubjectMap
                         , poms:Iterable[R2RMLPredicateObjectMap] , iQuery:IQuery) = {
    logger.info("Translating RDB data into RDF instances...");

    if(sm == null) {
      val errorMessage = "No SubjectMap is defined";
      logger.error(errorMessage);
      throw new Exception(errorMessage);
    }

    val logicalTableAlias = logicalTable.alias;

    val conn = this.connection
    val timeout = this.properties.databaseTimeout;
    val sqlQuery = iQuery.toString();
    val rows = DBUtility.execute(conn, sqlQuery, timeout);

    var mapXMLDatatype : Map[String, String] = Map.empty;
    var mapDBDatatype:Map[String, Integer]  = Map.empty;
    var rsmd : ResultSetMetaData = null;
    val datatypeMapper = new DatatypeMapper();

    try {
      rsmd = rows.getMetaData();
      val columnCount = rsmd.getColumnCount();
      for (i <- 0 until columnCount) {
        val columnName = rsmd.getColumnName(i+1);
        val columnType= rsmd.getColumnType(i+1);
        mapDBDatatype += (columnName -> new Integer(columnType));
        val mappedDatatype = datatypeMapper.getMappedType(columnType);
        //				if(mappedDatatype == null) {
        //					mappedDatatype = XSDDatatype.XSDstring.getURI();
        //				}
        if(mappedDatatype != null) {
          mapXMLDatatype += (columnName -> mappedDatatype);
        }


      }
    } catch {
      case e:Exception => {
        //e.printStackTrace();
        logger.warn("Unable to detect database columns!");
      }
    }

    val classes = sm.classURIs;
    val sgm = sm.graphMaps;

    var i=0;
    //var setSubjects : Set[RDFNode] = Set.empty;
    //var setBetaSub : Set[String] = Set.empty;

    var noOfErrors=0;
    while(rows.next()) {
      try {
        //translate subject map
        val subject = this.translateResultSet(rows, sm, logicalTableAlias, mapXMLDatatype);
        if(subject == null) {
          val errorMessage = "null value in the subject triple!";
          logger.debug("null value in the subject triple!");
          throw new Exception(errorMessage);
        }
        //setSubjects = setSubjects + subject._1
        //setBetaSub = setBetaSub + subject._2.toString
        //				val subjectString = subject.toString();
        //				this.materializer.createSubject(sm.isBlankNode(), subjectString);

        val subjectGraphs = sgm.flatMap(sgmElement=> {
          val subjectGraphValue = this.translateResultSet(rows, sgmElement, logicalTableAlias, mapXMLDatatype);
          //					val subjectGraphValue = this.translateData(sgmElement, unfoldedSubjectGraph, mapXMLDatatype);
          val graphMapTermType = sgmElement.inferTermType;
          val subjectGraph = graphMapTermType match {
            case Constants.R2RML_IRI_URI => {
              subjectGraphValue
            }
            case _ => {
              val errorMessage = "GraphMap's TermType is not valid: " + graphMapTermType;
              logger.warn(errorMessage);
              throw new Exception(errorMessage);
            }
          }
          if(subjectGraph == null) { None }
          else { Some(subjectGraph); }
        });


        //rdf:type
        classes.foreach(classURI => {
          //val statementObject = this.materializer.model.createResource(classURI);
          val statementObject = NodeFactory.createURI(classURI);
          if(subjectGraphs == null || subjectGraphs.isEmpty) {
            //						this.materializer.materializeRDFTypeTriple(subjectString, classURI, sm.isBlankNode(), null);
            this.materializer.materializeQuad(subject.node, RDF.`type`.asNode(), statementObject, null);
            this.materializer.writer.flush();
          } else {
            subjectGraphs.foreach(subjectGraph => {
              //							this.materializer.materializeRDFTypeTriple(subjectString, classURI, sm.isBlankNode(), subjectGraph);
              this.materializer.materializeQuad(subject.node, RDF.`type`.asNode(), statementObject, subjectGraph.node);
            });
          }
        });

        //translate predicate object map
        poms.foreach(pom => {
          val alias = if(pom.getAlias() == null) { logicalTableAlias; }
          else { pom.getAlias() }

          val predicates = pom.predicateMaps.flatMap(predicateMap => {
            val predicateValue = this.translateResultSet(rows, predicateMap, null, mapXMLDatatype);
            //						val predicateValue = this.translateData(predicateMap, unfoldedPredicateMap, mapXMLDatatype);
            if(predicateValue == null) { None }
            else { Some(predicateValue); }
          });

          val objects = pom.objectMaps.flatMap(objectMap => {
            val objectValue = this.translateResultSet(rows, objectMap, alias, mapXMLDatatype);
            //						val objectValue = this.translateData(objectMap, unfoldedObjectMap, mapXMLDatatype);
            if(objectValue == null) { None }
            else { Some(objectValue); }
          });

          val refObjects = pom.refObjectMaps.flatMap(refObjectMap => {
            val parentTripleMapName = refObjectMap.getParentTripleMapName;
            val parentTriplesMap = this.md.getParentTriplesMap(refObjectMap)
            val parentSubjectMap = parentTriplesMap.subjectMap;
            val parentTableAlias = this.unfolder.mapRefObjectMapAlias.getOrElse(refObjectMap, null);
            val parentSubjects = this.translateResultSet(rows, parentSubjectMap, parentTableAlias, mapXMLDatatype)
            //logger.info(s"parentSubjects = ${parentSubjects}")
            if(parentSubjects == null) { None }
            else { Some(parentSubjects) }
          })

          val pogm = pom.graphMaps;
          val predicateObjectGraphs = pogm.flatMap(pogmElement=> {
            val poGraphValue = this.translateResultSet(rows, pogmElement, null, mapXMLDatatype);
            //					  val poGraphValue = this.translateData(pogmElement, unfoldedPOGraphMap, mapXMLDatatype);
            if(poGraphValue == null) { None }
            else { Some(poGraphValue); }
          });


          if(sgm.isEmpty && pogm.isEmpty) {
            predicates.foreach(predicatesElement => {
              val quadSubject = subject.node;
              val predicateNode = predicatesElement.node;

              val quadGraph = null;
              objects.foreach(objectsElement => {
                val quadObject = objectsElement.node;

                this.materializer.materializeQuad(quadSubject, predicateNode, quadObject, quadGraph)
              });

              refObjects.foreach(refObjectsElement => {
                this.materializer.materializeQuad(quadSubject, predicateNode, refObjectsElement.node, quadGraph)
              });
            });
          } else {
            val unionGraphs = subjectGraphs ++ predicateObjectGraphs
            unionGraphs.foreach(unionGraph => {
              predicates.foreach(predicatesElement => {
                val predicateNode = predicatesElement.node;
                objects.foreach(objectsElement => {
                  unionGraphs.foreach(unionGraph => {
                    val tpS = subject.node;
                    //val tpP = predicatesElement._1;
                    val tpO = objectsElement.node;
                    val tpG = unionGraph.node;
                    this.materializer.materializeQuad(tpS, predicateNode, tpO, tpG);
                  })
                });

                refObjects.foreach(refObjectsElement => {
                  this.materializer.materializeQuad(subject.node, predicateNode, refObjectsElement.node, unionGraph.node)
                });

              });
            })
          }

        });
        i = i+1;
      } catch {
        case e:Exception => {
          noOfErrors = noOfErrors + 1;
          val errorMessage = e.getMessage;
          e.printStackTrace();
          logger.error("error while translating data: " + errorMessage);
        }
      }
    }

    if(noOfErrors > 0) {
      logger.debug("Error when generating " + noOfErrors + " triples, check log file for details!");
    }

    logger.info(i + " instances retrieved.");
    //logger.debug(setSubjects.size + " unique instances (URI) retrieved.");
    //logger.debug(setBetaSub.size + " unique instances (DB Column) retrieved.");
    rows.close();

  }


  def visit(triplesMap:R2RMLTriplesMap) : Object = {
    //		String sqlQuery = triplesMap.accept(
    //				new R2RMLElementUnfoldVisitor()).toString();
    this.translateData(triplesMap);
    null;
  }

  override def generateRDFTriples(cm:MorphBaseClassMapping , iQuery:IQuery ) = {
    val triplesMap = cm.asInstanceOf[R2RMLTriplesMap];
    val logicalTable = triplesMap.getLogicalTable().asInstanceOf[R2RMLLogicalTable];
    val sm = triplesMap.subjectMap;
    val poms = triplesMap.predicateObjectMaps;
    this.generateRDFTriples(logicalTable, sm, poms, iQuery);
  }

  override def generateSubjects(cm:MorphBaseClassMapping, iQuery:IQuery) = {
    val triplesMap = cm.asInstanceOf[R2RMLTriplesMap];
    val logicalTable = triplesMap.getLogicalTable().asInstanceOf[R2RMLLogicalTable];
    val sm = triplesMap.subjectMap;
    this.generateRDFTriples(logicalTable, sm, Nil, iQuery);
    //conn.close();
  }



  def translateDateTime(value:String) = {
    value.toString().trim().replaceAll(" ", "T");
  }

  def translateDate(value:String) = {
    //val dfInput = new SimpleDateFormat("dd-MMM-yyy", Locale.ENGLISH);
    val result = dfInput.parse(value);
    val dfOutput = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
    val result2 = dfOutput.format(result);
    result2.toString();
  }

  def translateBoolean(value:String) = {
    if(value.equalsIgnoreCase("T")  || value.equalsIgnoreCase("True") || value.equalsIgnoreCase("1")) {
      "true";
    } else if(value.equalsIgnoreCase("F") || value.equalsIgnoreCase("False") || value.equalsIgnoreCase("0")) {
      "false";
    } else {
      "false";
    }
  }

  def createLiteral(value:Object, datatype:Option[String]
                    , language:Option[String]) : Literal = {
    try {
      val encodedValueAux = GeneralUtility.encodeLiteral(value.toString());
      //			val encodedValue = if(this.properties != null) {
      //				if(this.properties.literalRemoveStrangeChars) {
      //				  GeneralUtility.removeStrangeChars(encodedValueAux);
      //				} else { encodedValueAux }
      //			} else { encodedValueAux }
      val encodedValue = encodedValueAux;

      val valueWithDataType = if(datatype.isDefined && datatype.get != null) {
        val datatypeGet = datatype.get;


        //				datatypeGet match {
        //					case xsdDateTimeURI => {
        //					  this.translateDateTime(encodedValue);
        //					}
        //					case xsdBooleanURI => {
        //					  this.translateBoolean(encodedValue);
        //				  	}
        //					case _ => {
        //					  encodedValue
        //					}
        //				  }

        if(datatypeGet.equals(xsdDateTimeURI)) {
          this.translateDateTime(encodedValue);
        }
        else if (datatypeGet.equals(xsdBooleanURI)) {
          this.translateBoolean(encodedValue);
        }
        else if(datatypeGet.equals(xsdDurationURI)) {
          encodedValue;
        }
        else if(datatypeGet.equals(xsdDateURI)) {
          this.translateDate(encodedValue)
        }
        else {
          encodedValue
        }
      } else { encodedValue }

      val result:Literal = if(language.isDefined) {
        this.materializer.model.createLiteral(valueWithDataType, language.get);
      } else {
        if(datatype.isDefined) {
          this.materializer.model.createTypedLiteral(valueWithDataType, datatype.get);
        } else {
          this.materializer.model.createLiteral(valueWithDataType);
        }
      }

      //			val result:Literal = if(datatype.isDefined) {
      //			  this.materializer.model.createTypedLiteral(encodedValue, datatype.get);
      //			} else {
      //				if(language.isDefined) {
      //				  this.materializer.model.createLiteral(encodedValue, language.get);
      //				} else {
      //				  this.materializer.model.createLiteral(encodedValue);
      //				}
      //			}
      result
    } catch {
      case e:Exception => {
        logger.warn("Error translating value : " + value);
        throw e
      }
    }
  }

  //	def translateData2(termMap:R2RMLTermMap, originalValue:Object
  //	    , mapXMLDatatype : Map[String, String]) = {
  //		val translatedValue:String = termMap.inferTermType match {
  //		  case Constants.R2RML_IRI_URI => {
  //			 this.translateIRI(originalValue.toString());
  //		  }
  //		  case Constants.R2RML_LITERAL_URI => {
  //			  this.translateLiteral(termMap, originalValue, mapXMLDatatype);
  //		  }
  //		  case Constants.R2RML_BLANKNODE_URI => {
  //		    val resultBlankNode = GeneralUtility.createBlankNode(originalValue.toString());
  //		    resultBlankNode
  //		  }
  //		  case _ => {
  //			  originalValue.toString()
  //		  }
  //		}
  //		translatedValue
  //	}

  def translateBlankNode(value:Object) = {

  }

  def generateRDFNode(nodeValue:Object
                   , termMap:R2RMLTermMap
                   , datatype:Option[String]
                   //                   , termMapType:String
                   //                       , mapXMLDatatype : Map[String, String]
                  ) = {
    val result = if(nodeValue != null) {
      //val termMapType = termMap.inferTermType;
      termMap.inferTermType match {
        case Constants.R2RML_IRI_URI => {
          if(termMap.isInstanceOf[R2RMLPredicateMap]) {
            this.createProperty(nodeValue.toString());
          } else {
            this.createResource(nodeValue.toString());
          }
        }
        case Constants.R2RML_LITERAL_URI => {
          /*
          val datatype = if(termMap.datatype.isDefined) { termMap.datatype }
          else {
            val datatypeAux = {
              val columnNameAux = termMap.columnName.replaceAll("\"", "");
              if(mapXMLDatatype != null) {
                val columnNameAuxDatatype = mapXMLDatatype.get(columnNameAux);
                if(columnNameAuxDatatype != None) { columnNameAuxDatatype }
                else { mapXMLDatatype.get(columnTermMapValue); }
              } else {
                None
              }
            }
            datatypeAux
          }
          */

          this.createLiteral(nodeValue, datatype, termMap.languageTag);
        }
        case Constants.R2RML_BLANKNODE_URI => {
          val anonId = new AnonId(nodeValue.toString());
          this.materializer.model.createResource(anonId)
        }
        case _ => {
          null
        }
      }
    } else {
      null
    }
    result
  }

  def generateNode(nodeValue:String
                      , termMap:R2RMLTermMap
                      , datatype:Option[String]
                      //                   , termMapType:String
                      //                       , mapXMLDatatype : Map[String, String]
                     ) = {
    val result = if(nodeValue != null) {
      //val termMapType = termMap.inferTermType;
      termMap.inferTermType match {
        case Constants.R2RML_IRI_URI => {
          NodeFactory.createURI(nodeValue);
        }
        case Constants.R2RML_LITERAL_URI => {

          /*
          val datatype = if(termMap.datatype.isDefined) { termMap.datatype }
          else {
            val datatypeAux = {
              val columnNameAux = termMap.columnName.replaceAll("\"", "");
              if(mapXMLDatatype != null) {
                val columnNameAuxDatatype = mapXMLDatatype.get(columnNameAux);
                if(columnNameAuxDatatype != None) { columnNameAuxDatatype }
                else { mapXMLDatatype.get(columnTermMapValue); }
              } else {
                None
              }
            }
            datatypeAux
          }
          */

          //this.createLiteral(nodeValue, datatype, termMap.languageTag);
          if(termMap.languageTag == null || termMap.languageTag.isEmpty) {
            if(datatype == null || datatype.isEmpty) {
              NodeFactory.createLiteral(nodeValue)
            } else {
              val rdfDataType = GeneralUtility.getXSDDatatype(datatype.get)
              NodeFactory.createLiteral(nodeValue, rdfDataType);
            }
          } else {
            if(datatype == null || datatype.isEmpty) {
              NodeFactory.createLiteral(nodeValue, termMap.languageTag.get)
            } else {
              val rdfDataType = GeneralUtility.getXSDDatatype(datatype.get)
              NodeFactory.createLiteral(nodeValue, termMap.languageTag.get, rdfDataType);
            }
          }


        }
        case Constants.R2RML_BLANKNODE_URI => {
          val anonId = new AnonId(nodeValue.toString());
          //this.materializer.model.createResource(anonId)
          NodeFactory.createBlankNode(anonId.getLabelString)
        }
        case _ => {
          null
        }
      }
    } else {
      null
    }
    result
  }


  def translateResultSet(rs:ResultSet, termMap:R2RMLTermMap, logicalTableAlias:String
                         , mapXMLDatatype : Map[String, String]
                         //) : (RDFNode, List[Object]) = {
                        ) : TranslatedValue = {

    val dbEnclosedCharacter = Constants.getEnclosedCharacter(dbType);
    val inferedTermType = termMap.inferTermType();


    val result = termMap.termMapType match {
      case Constants.MorphTermMapType.ColumnTermMap => {
        val columnTermMapValue = if(logicalTableAlias != null && !logicalTableAlias.equals("")) {
          val termMapColumnValueSplit = termMap.columnName.split("\\.");
          //val columnName = termMapColumnValueSplit(termMapColumnValueSplit.length - 1).replaceAll("\"", dbEnclosedCharacter);
          //val columnName = termMapColumnValueSplit(termMapColumnValueSplit.length - 1).replaceAll(dbEnclosedCharacter, "");
          val columnName = termMapColumnValueSplit(termMapColumnValueSplit.length - 1).replaceAllLiterally("\\\"", "");

          logicalTableAlias + "_" + columnName;
        }
        else { termMap.columnName }

        val dbValueAux = this.getResultSetValue(termMap.datatype, rs, columnTermMapValue);
        //			  val dbValue = dbValueAux match {
        //				  case dbValueAuxString:String => {
        //					  if(this.properties.transformString.isDefined) {
        //					    this.properties.transformString.get match {
        //						    case Constants.TRANSFORMATION_STRING_TOLOWERCASE => {
        //						      dbValueAuxString.toLowerCase();
        //						    }
        //						    case Constants.TRANSFORMATION_STRING_TOUPPERCASE => {
        //						      dbValueAuxString.toUpperCase();
        //						    }
        //						    case _ => { dbValueAuxString }
        //					    }
        //
        //					  }
        //					  else { dbValueAuxString }
        //				  }
        //				  case _ => { dbValueAux }
        //			  }
        //val dbValue = dbValueAux;
        val dbValue  = if(Constants.DATABASE_H2_NULL_VALUE.equals(dbValueAux)
          && Constants.DATABASE_CSV.equals(dbType)) {
          null
        } else {
          dbValueAux
        }

        val datatype = if(termMap.datatype.isDefined) { termMap.datatype }
        else {
          val columnNameAux = termMap.columnName.replaceAll("\"", "");
          val datatypeAux = {
            val columnNameAuxDatatype = mapXMLDatatype.get(columnNameAux);
            if(columnNameAuxDatatype != None) { columnNameAuxDatatype }
            else { mapXMLDatatype.get(columnTermMapValue); }
          }
          datatypeAux
        }
        //val result = (this.generateNode(dbValue, termMap, datatype), List(dbValue));
        if(dbValue != null) {
          val nodeValue = dbValue.toString
          new TranslatedValue(this.generateNode(nodeValue, termMap, datatype), List(dbValue));
        } else {
          new TranslatedValue(null, List(dbValue));
        }
      }
      case Constants.MorphTermMapType.ConstantTermMap => {
        val datatype = if(termMap.datatype.isDefined) { termMap.datatype } else { None }
        new TranslatedValue(this.generateNode(termMap.constantValue, termMap, datatype), List());
      }
      case Constants.MorphTermMapType.TemplateTermMap => {
        val datatype = if(termMap.datatype.isDefined) { termMap.datatype } else { None }
        var rawDBValues:List[Object] = Nil;
        val termMapTemplateString = termMap.templateString.replaceAllLiterally("\\\"", dbEnclosedCharacter);

        val attributes = RegexUtility.getTemplateColumns(termMapTemplateString, true);
        val replacements:Map[String, String] = attributes.flatMap(attribute => {
          val databaseColumn = if(logicalTableAlias != null) {
            val attributeSplit = attribute.split("\\.");
            if(attributeSplit.length >= 1) {
              val columnName = attributeSplit(attributeSplit.length - 1).replaceAll("\"", dbEnclosedCharacter);
              logicalTableAlias + "_" + columnName;
            }
            else { logicalTableAlias + "_" + attribute; }
          } else { attribute; }

          val dbValueAux = this.getResultSetValue(termMap.datatype, rs, databaseColumn);
          //logger.info(s"dbValueAux = ${dbValueAux}")


          if(dbValueAux != null) {
            rawDBValues = rawDBValues ::: List(dbValueAux);
          }


          val dbValue = dbValueAux match {
            case dbValueAuxString:String => {
              if(this.properties.transformString.isDefined) {
                this.properties.transformString.get match {
                  case Constants.TRANSFORMATION_STRING_TOLOWERCASE => {
                    dbValueAuxString.toLowerCase();
                  }
                  case Constants.TRANSFORMATION_STRING_TOUPPERCASE => {
                    dbValueAuxString.toUpperCase();
                  }
                  case _ => { dbValueAuxString }
                }

              }
              else { dbValueAuxString }
            }
            case _ => { dbValueAux }
          }
          //logger.info(s"dbValue = ${dbValue}")


          if(dbValue != null) {
            var databaseValueString = dbValue.toString();
            if(termMap.inferTermType.equals(Constants.R2RML_IRI_URI)) {
              val uriTransformationOperations = this.properties.uriTransformationOperation;
              if(uriTransformationOperations != null) {
                uriTransformationOperations.foreach{
                  case Constants.URI_TRANSFORM_TOLOWERCASE => {
                    databaseValueString = databaseValueString.toLowerCase();
                  }
                  case Constants.URI_TRANSFORM_TOUPPERCASE => {
                    databaseValueString = databaseValueString.toUpperCase();
                  }
                  case _ => { }
                }
              }
            }

            Some(attribute -> databaseValueString);
          } else {
            None
            //Some(attribute -> "null");
          }
        }).toMap

        //logger.info(s"replacements = ${replacements}")
        //logger.info(s"termMapTemplateString = ${termMapTemplateString}")
        if(replacements.isEmpty) {
          //(this.translateData(termMap, termMapTemplateString, datatype), rawDBValues);
          new TranslatedValue(null, rawDBValues);
        } else {
          val templateWithDBValue = RegexUtility.replaceTokens(termMapTemplateString, replacements);
          if(templateWithDBValue != null) {
            new TranslatedValue(this.generateNode(templateWithDBValue, termMap, datatype), rawDBValues);
          } else {
            new TranslatedValue(null, rawDBValues)
          }
        }
      }
    }

    result
  }

  def getResultSetValue(termMapDatatype:Option[String], rs:ResultSet, pColumnName:String ) : Object = {
    try {
      val dbType = this.properties.databaseType;
      val dbEnclosedCharacter = Constants.getEnclosedCharacter(dbType);

      //val logicalTableMetaData = ownerTriplesMap.getLogicalTable();

      //val dbType = this.configurationProperties.databaseType;
      //val dbType = logicalTableMetaData.getTableMetaData.dbType;
      //val zConstant = MorphSQLConstant(pColumnName, ZConstant.COLUMNNAME, dbType);
      val zConstant = MorphSQLConstant(pColumnName, ZConstant.COLUMNNAME);
      val tableName = zConstant.table;
      //val columnNameAux = zConstant.column.replaceAll("\"", "")
      val columnNameAux = zConstant.column.replaceAll(dbEnclosedCharacter, ""); //doesn't work for 9a
      //val columnNameAux = zConstant.column


      val columnName = {
        if(tableName != null) {
          tableName + "." + columnNameAux
        } else {
          columnNameAux
        }
      }


      val result = if(termMapDatatype == null) {
        rs.getString(columnName);
      } else if(!termMapDatatype.isDefined) {
        rs.getString(columnName);
      }
      //			else if(termMapDatatype.get.equals(XSDDatatype.XSDdateTime.getURI())) {
      //				val rsDateValue = rs.getDate(columnName);
      //				if(rsDateValue == null) { null; } else { rsDateValue.toString(); }
      //			}
      else {
        rs.getObject(columnName);
      }
      result
    } catch {
      case e:Exception => {
        e.printStackTrace();
        logger.error("error occured when translating result: " + e.getMessage());
        null
      }
    }
  }
}