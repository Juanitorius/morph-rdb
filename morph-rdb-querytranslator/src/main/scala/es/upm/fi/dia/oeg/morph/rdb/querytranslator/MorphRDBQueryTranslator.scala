package es.upm.fi.dia.oeg.morph.rdb.querytranslator

import scala.collection.JavaConversions._
import java.sql.Connection
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.apache.log4j.Logger
import Zql.ZConstant
import Zql.ZExp
import com.hp.hpl.jena.graph.Node
import com.hp.hpl.jena.graph.Triple
import es.upm.fi.dia.oeg.morph.base.CollectionUtility
import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.RegexUtility
import es.upm.fi.dia.oeg.obdi.core.ConfigurationProperties
import es.upm.fi.dia.oeg.obdi.core.ODEMapsterUtility
import es.upm.fi.dia.oeg.obdi.core.engine.AbstractResultSet
import es.upm.fi.dia.oeg.obdi.core.engine.AbstractUnfolder
import es.upm.fi.dia.oeg.obdi.core.exception.InsatisfiableSQLExpression
import es.upm.fi.dia.oeg.obdi.core.model.AbstractConceptMapping
import es.upm.fi.dia.oeg.obdi.core.model.AbstractMappingDocument
import es.upm.fi.dia.oeg.obdi.core.model.AbstractPropertyMapping
import es.upm.fi.dia.oeg.obdi.core.sql.IQuery
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.R2RMLUtility
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.engine.R2RMLUnfolder
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLMappingDocument
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLRefObjectMap
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTermMap
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTermMap.TermMapType
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTriplesMap
import es.upm.fi.dia.oeg.obdi.core.engine.IQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseBetaGenerator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseCondSQLGenerator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseAlphaGenerator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBasePRSQLGenerator
import es.upm.fi.dia.oeg.morph.base.querytranslator.NameGenerator
import es.upm.fi.dia.oeg.morph.base.TermMapResult

class MorphRDBQueryTranslator() 
    extends MorphBaseQueryTranslator() {
	override val logger = Logger.getLogger("MorphQueryTranslator");
	
	//chebotko functions
	val alphaGenerator:MorphBaseAlphaGenerator = new MorphRDBAlphaGenerator(this);
	val betaGenerator:MorphBaseBetaGenerator = new MorphRDBBetaGenerator(this);
	val condSQLGenerator:MorphBaseCondSQLGenerator = new MorphRDBCondSQLGenerator(this);
	val prSQLGenerator:MorphBasePRSQLGenerator = new MorphRDBPRSQLGenerator(this);
	
	var mapTripleAlias:Map[Triple, String] = Map.empty;
	var mapTemplateMatcher:Map[String, Matcher] = Map.empty;
	var mapTemplateAttributes:Map[String, java.util.List[String]] = Map.empty;
	val unfolder = new R2RMLUnfolder();

	override def transIRI(node:Node) : List[ZExp] = {
		val cms = mapInferredTypes(node);
		val cm = cms.iterator().next().asInstanceOf[R2RMLTriplesMap];
		val mapColumnsValues = cm.getSubjectMap().getTemplateValues(node.getURI());
		val result:List[ZExp] = {
			if(mapColumnsValues == null || mapColumnsValues.size() == 0) {
				//do nothing
			  Nil
			} else {
				val resultAux = mapColumnsValues.keySet().map(column => {
					val value = mapColumnsValues.get(column);
					val constant = new ZConstant(value, ZConstant.UNKNOWN);
					constant;			  
				})
				resultAux.toList;
			}		  
		}

		result;
	}

//	override def buildAlphaGenerator() = {
//		val alphaGenerator = new MorphRDBAlphaGenerator(this);
//		super.setAlphaGenerator(alphaGenerator);
//		
//	}
//
//	override def buildBetaGenerator() = {
//		val betaGenerator = new MorphRDBBetaGenerator(this);
//		super.setBetaGenerator(betaGenerator);
//	}
//
//	override def buildCondSQLGenerator() = {
//		val condSQLGenerator = new MorphRDBCondSQLGenerator(this);
//		super.setCondSQLGenerator(condSQLGenerator);
//	}
//
//	override def buildPRSQLGenerator() = {
//		val prSQLGenerator = new MorphRDBPRSQLGenerator(this);
//		super.setPrSQLGenerator(prSQLGenerator);
//	}

	def getMappedMappingByVarName(varName:String, rs:AbstractResultSet) = {
		val mapValue = {
			try {
				val mappingHashCode = rs.getInt(Constants.PREFIX_MAPPING_ID + varName);
				if(mappingHashCode == null) {
					val varNameHashCode = varName.hashCode();
					super.getMappedMapping(varNameHashCode);
				} else {
					super.getMappedMapping(mappingHashCode);
				}
			} catch {
			  case e:Exception => {
			    null
			  }
			}				  
		}

		mapValue;
	}
	
	override def translateResultSet(varName:String , rs:AbstractResultSet ) : TermMapResult  = {
		val result:TermMapResult = {
		try {
			if(rs != null) {
				val rsColumnNames = rs.getColumnNames();
				val columnNames = CollectionUtility.getElementsStartWith(rsColumnNames, varName + "_");
				//val columnNames = CollectionUtility.getElementsStartWith(rsColumnNames, varName);

				val mapValue = this.getMappedMappingByVarName(varName, rs);
				
				if(!mapValue.isDefined) {
					val originalValue = rs.getString(varName);
					new TermMapResult(originalValue, null, null)
				} else {
					val termMap : R2RMLTermMap = {
						mapValue.get match {
						  case mappedValueTermMap:R2RMLTermMap => {
						    mappedValueTermMap;
						  }
						  case mappedValueRefObjectMap:R2RMLRefObjectMap => {
							mappedValueRefObjectMap.getParentTriplesMap().getSubjectMap();
						  }
						  case _ => {
						    logger.debug("undefined type of mapping!");
						    null
						  }
						}					  
					}


					val resultAux = {
						if(termMap != null) {
							val termMapType = termMap.getTermMapType();
	
							if(termMapType == TermMapType.TEMPLATE) {
								val templateString = termMap.getTemplateString();
								if(this.mapTemplateMatcher.contains(templateString)) {
									val matcher = this.mapTemplateMatcher.get(templateString);  
								} else {
									val pattern = Pattern.compile(Constants.R2RML_TEMPLATE_PATTERN);
									val matcher = pattern.matcher(templateString);
									this.mapTemplateMatcher += (templateString -> matcher);							  
								}
								
								val templateAttributes = {
									if(this.mapTemplateAttributes.contains(templateString)) {
										this.mapTemplateAttributes(templateString);  
									} else {
										val templateAttributesAux = RegexUtility.getTemplateColumns(templateString, true);
										this.mapTemplateAttributes += (templateString -> templateAttributesAux);
										templateAttributesAux;
									}							  
								}
	
								var i = 0;
								val replaceMentAux = templateAttributes.map(templateAttribute => {
									val columnName = {
										if(columnNames == null || columnNames.isEmpty()) {
											varName;
										} else {
											varName + "_" + i;
										}								  
									}
									i = i + 1;
	
									val dbValue = rs.getString(columnName);
									templateAttribute -> dbValue;
								})
								val replacements = replaceMentAux.toMap;
								
								if(replacements.size() > 0) {
									RegexUtility.replaceTokens(templateString, replacements);	
								} else {
									logger.debug("no replacements found for the R2RML template!");
									null;
								}
							} else if(termMapType == TermMapType.COLUMN) {
								//String columnName = termMap.getColumnName();
								rs.getString(varName);
							} else if (termMapType == TermMapType.CONSTANT) {
								termMap.getConstantValue();
							} else {
								logger.debug("Unsupported term map type!");
								null;
							}
						} else {
						  null;
						}				  
					}
					
					val termMapType = termMap.getTermType();
					val xsdDatatype = termMap.getDatatype();
					val resultAuxString = {
						if(resultAux != null) {
							if(termMapType != null) {
								if(termMapType.equals(Constants.R2RML_IRI_URI)) {
									ODEMapsterUtility.encodeURI(resultAux);
								} else if(termMapType.equals(Constants.R2RML_LITERAL_URI)) {
									ODEMapsterUtility.encodeLiteral(resultAux);
								} else {
								  resultAux
								}
							} else {
							  resultAux
							}
						} else {
						  null
						}					  
					}
					new TermMapResult(resultAuxString, termMapType, xsdDatatype);
					//resultAuxString;
				}
			} else {
			  null
			}
		} catch {
		  case e:Exception => {
		    logger.debug("Error occured while translating result set : " + e.getMessage());
		    null;
		  }
		}		  
		}

		result;
	}

	override def transTP(tp:Triple , cm:AbstractConceptMapping ,predicateURI:String 
	    , pm:AbstractPropertyMapping ) : IQuery = {
		// TODO Auto-generated method stub
		null;
	}

	override def getTripleAlias(tp:Triple ) : String = {
	  if(this.mapTripleAlias.contains(tp)) {
	    this.mapTripleAlias(tp);
	  } else {
	    null
	  }
	}

	override def putTripleAlias(tp:Triple , alias:String ) = {
		this.mapTripleAlias += (tp -> alias);
	}
	
	   
	   
	//def getMappingDocument(): es.upm.fi.dia.oeg.obdi.core.model.AbstractMappingDocument = ???   
	//def getOptimizer(): es.upm.fi.dia.oeg.obdi.core.engine.IQueryTranslationOptimizer = ???   
	   
	   
	   
	//def setConnection(x$1: java.sql.Connection): Unit = ???   
	   
	//def setIgnoreRDFTypeStatement(x$1: Boolean): Unit = ???   
	   
	   
	//def setUnfolder(x$1: es.upm.fi.dia.oeg.obdi.core.engine.AbstractUnfolder): Unit = ???	
}

object MorphRDBQueryTranslator {
	val nameGenerator:NameGenerator = new NameGenerator();

	def createQueryTranslator(mappingDocument:AbstractMappingDocument, conn:Connection ) 
	: IQueryTranslator = {

		val queryTranslator = new MorphRDBQueryTranslator();

		if(conn != null) {
			mappingDocument.setConn(conn)
		}
		queryTranslator.setMappingDocument(mappingDocument);
		queryTranslator;
	}
		
	def createQueryTranslator(mappingDocument:AbstractMappingDocument) 
	: IQueryTranslator = {
		MorphRDBQueryTranslator.createQueryTranslator(mappingDocument, null);
	}

	def createQueryTranslator(mappingDocumentPath:String) : IQueryTranslator = {
		MorphRDBQueryTranslator.createQueryTranslator(mappingDocumentPath, null);
	}

	def createQueryTranslator(mappingDocumentPath:String , conn:Connection ) : IQueryTranslator = {
		val mappingDocument = new R2RMLMappingDocument(mappingDocumentPath, null);
		MorphRDBQueryTranslator.createQueryTranslator(mappingDocument, conn);
	}
}