package edu.colorado.droidel.codegen

import java.io.{File, FileWriter, StringWriter}
import java.util.EnumSet
import javax.lang.model.element.Modifier.{FINAL, PUBLIC, STATIC}
import com.ibm.wala.classLoader.{IClass, IMethod}
import com.ibm.wala.ipa.cha.IClassHierarchy
import com.ibm.wala.types.{ClassLoaderReference, FieldReference, TypeReference}
import com.squareup.javawriter.JavaWriter
import edu.colorado.droidel.codegen.AndroidHarnessGenerator._
import edu.colorado.droidel.constants.{AndroidConstants, DroidelConstants}
import edu.colorado.droidel.util.{CHAUtil, ClassUtil, JavaUtil, Timer}
import scala.collection.JavaConversions._
import com.ibm.mobile.droidertemplate.WriterFactory

object AndroidHarnessGenerator {
  private val DEBUG = false
}

class AndroidHarnessGenerator(cha : IClassHierarchy, instrumentationVars : Iterable[FieldReference]) {  
  // type aliases to make some type signatures more clear
  type Expression = String
  type Statement = String
  type VarName = String  
  
  val inhabitor = new TypeInhabitor
  val inhabitantCache = inhabitor.inhabitantCache
  
  // TODO: there can be multiple instrumentation vars of the same type. only one will be in the map. this may be undesirable  
  instrumentationVars.foreach(f => inhabitantCache.put(cha.lookupClass(f.getFieldType()), f.getName().toString()))
  
  var initAllocs = List.empty[Statement]  
  
  def makeSpecializedViewInhabitantCache(stubPaths : Iterable[File]) = {
    def makeClass(className : String) : IClass = 
      cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Primordial, ClassUtil.walaifyClassName(className)))
      
    val layoutStubClass = s"${DroidelConstants.STUB_DIR}.${DroidelConstants.LAYOUT_STUB_CLASS}"

    val viewClass = makeClass(AndroidConstants.VIEW_TYPE)

    val (alloc, freshVar) = inhabitor.mkAssign(viewClass, s"$layoutStubClass.findViewById(-1)")
    initAllocs = alloc :: initAllocs
    inhabitantCache.put(viewClass, freshVar)
  }  
  
  // take framework-allocated types and FieldReference's corresponding to instrumentation variables as input 
  def generateHarness(frameworkCreatedTypesCallbackMap : Map[IClass,Set[IMethod]],
                      manifestDeclaredCallbackMap : Map[IClass,Set[IMethod]],                         
                      instrumentedBinDir : String,
                      androidJarPath : String) : String = {   

    val harnessDir = new File(s"${instrumentedBinDir}/${DroidelConstants.HARNESS_DIR}")
    if (!harnessDir.exists()) harnessDir.mkdir()        
    
    // TODO: don't allocate new Fragment instances here -- get them by calling the layout stubs findFragmentById or specialized getters
    
    // create an instance of each framework-allocated type. return a list of allocations to emit 
    val allocStatements = frameworkCreatedTypesCallbackMap.keys.foldLeft (initAllocs) ((allocs, appType) => {
      // this will fill up the inhabitant cache with mappings for each framework created type
      val (inhabitant, newAllocs) = inhabitor.inhabit(appType.getReference(), cha, allocs)
      // if we inhabit a subtype of appType rather than appType itself, there will be no entry
      // for appType in the cache (the cache will contain the mapping subtype -> inhabitant). 
      // fix this oddity by adding a mapping from appType -> to inhabitant
      if (!inhabitantCache.contains(appType)) inhabitantCache.put(appType, inhabitant)
      newAllocs
    })        
        
    // TODO: do something smarter here too -- use our hardcoded list of callback classes and callback methods within those classes
    def getFrameworkCallbacksOnInterfaceType(interfaceType : IClass) : Iterable[IMethod] = {
      require(interfaceType.isInterface())
      interfaceType.getDeclaredMethods().filter(m => m.isPublic())
    }    
    
    // create statements invoking all lifecycle and manifest-defined callbacks on each of our framework-created types
    val (frameworkCreatedCbCalls, allocStatements1) = frameworkCreatedTypesCallbackMap.foldLeft (List.empty[Statement],allocStatements) ((l, entry) => {
      val frameworkCreatedClass = entry._1
      val varName = inhabitantCache(frameworkCreatedClass) // this lookup will never fail because we've already allocated an instance of each framework-created type
      entry._2.foldLeft (l) ((l, m) => {      
        val (call, newAllocs) = inhabitor.inhabitFunctionCall(m, Some(varName), cha, l._2)
        (call :: l._1, newAllocs)
      })
    })    
        
    // invoke callbacks on framework-created types that extend callback interfaces
    val (frameworkCreatedInterfaceCbCalls, allocStatements2) = 
      frameworkCreatedTypesCallbackMap.keys.foldLeft (List.empty[Statement],allocStatements1) ((l, frameworkCreatedClass) => {
        val varName = inhabitantCache(frameworkCreatedClass) 
        
        def getAllParameterTypes(m : IMethod) : Iterable[TypeReference] = {
          (0 to m.getNumberOfParameters() - 1).map(i => m.getParameterType(i))
        }
        
        frameworkCreatedClass.getAllImplementedInterfaces().foldLeft (l) ((l, interfaceType) => {          
          // TODO: somewhat of a hack -- only invoke callback methods that our class directly overrides. 
          // this can miss callbacks that an application-scope superclass overrides
          val frameworkMethodsByName = frameworkCreatedClass.getDeclaredMethods().groupBy(m => m.getName())
          interfaceType.getDeclaredMethods().filter(m => m.isPublic() && !m.isStatic && CHAUtil.mayOverride(m, frameworkCreatedClass, cha)).foldLeft (l) ((l, m) => {
            val possibleOverrides = frameworkMethodsByName(m.getName())
                                    .filter(possibleOverride => !possibleOverride.isStatic() &&
                                            possibleOverride.getNumberOfParameters() == m.getNumberOfParameters())
            val toInhabit = if (possibleOverrides.size > 1) {
              // special case to handle generic methods, when we'll have one method with a parameter that is Object and one with a more specific type
              // TODO: this is a big hack. do better
              val objName = TypeReference.JavaLangObject.getName()
              val lessGeneralMethods = possibleOverrides.filter(overrideMethod => overrideMethod.getReturnType().getName() != objName && 
                                                                getAllParameterTypes(overrideMethod).forall(typ => typ.getName() != objName) && {
                (0 to m.getNumberOfParameters() - 1).forall(i => // check for covariance in parameter types  
                  CHAUtil.isAssignableFrom(m.getParameterType(i), overrideMethod.getParameterType(i), cha)                 
                )
              })
              if (lessGeneralMethods.isEmpty) None
              else Some(lessGeneralMethods.head) // pick one arbitrarily               
            } else Some(m)
            
            toInhabit match {
              case Some(toInhabit) =>
                 val (call, allocs) = inhabitor.inhabitFunctionCall(toInhabit, Some(varName), cha, l._2)
                 (call :: l._1, allocs)
              case None =>
                 if (DEBUG) sys.error(s"Couldn't find override for $m in $frameworkCreatedClass")
                 l
            }         
          })
        })
    })    

    // create statements invoking callbacks on each instrumentation variable. note that although an application-allocated object can extend
    // multiple callback interfaces, we create an instrumentation variable for each CB interface it extends. thus, here it is sufficient to
    // invoke the callback methods defined on the type of each instrumentation var. To be concrete, here's an example of how we instrument
    // an application-created type that extends multiple callback interfaces:
    // 
    // class CBObj implements CallbackA, CallbackB
    // ...
    // x = new CBObj();
    // Harness.instrumented_CallbackA_1 = x; // added via instrumentation
    // Harness.instrumented_CallbackB_1 = x; // added via instrumentation
    // ...
    // class Harness
    // static CallbackA instrumented_CallbackA_1;
    // static CallbackB instrumented_CallbackB_1;
    // main() { instrumented_CallbackA_1.cbA(); instrumented_CallbackB_1.cbB(); }
    val (instrumentationVarCbCalls, finalAllocStatements) = instrumentationVars.foldLeft (List.empty[Statement], allocStatements2) ((l, v) => 
      getFrameworkCallbacksOnInterfaceType(CHAUtil.lookupClass(v.getFieldType(), cha)).foldLeft (l) ((l, m) => {
        val (call, finalAllocStatements) = inhabitor.inhabitFunctionCall(m, Some(v.getName().toString()), cha, l._2)
        (call :: l._1, finalAllocStatements)
      })      
    )   
    
    val strWriter = new StringWriter
    
        val harnessWriter = WriterFactory.factory(strWriter);
         
    harnessWriter.emitBegin();

    // emit static fields for each of our instrumentation variables
    instrumentationVars.foreach(field => harnessWriter.emitField(ClassUtil.deWalaifyClassName(field.getFieldType()), 
                                                          field.getName().toString(),
                                                          EnumSet.of(PUBLIC, STATIC)))   
                                                          
    harnessWriter.emitBeginHarness();
    
    harnessWriter.beginAllocationComponent;
    
    // emit allocations. need to reverse because the list of allocations was populated by prepending the most recent allocation
    // (which may in turn depend on other allocations), so we want to go last to first
    finalAllocStatements.reverse.foreach(alloc => harnessWriter.emitAllocationComponent(alloc))
    
    harnessWriter.endAllocationComponent;
    
    harnessWriter.beginCallToComponent;
    
    // emit lifecycle callbacks on framework-created types
    frameworkCreatedCbCalls.foreach(invoke => harnessWriter.emitCallToComponent(invoke))
    // emit implemented interface callbacks on framework-created types
    frameworkCreatedInterfaceCbCalls.foreach(invoke => harnessWriter.emitCallToComponent(invoke))
    // emit instrumentation var callback invocations
    instrumentationVarCbCalls.foreach(invoke => harnessWriter.emitCallToComponent(invoke))
    
    harnessWriter.endCallToComponent;
    
    harnessWriter.emitEndHarness();
    harnessWriter.emitEnd();
    
    
    val harnessPath = s"${harnessDir.getAbsolutePath()}/${DroidelConstants.HARNESS_CLASS}"

    harnessWriter.write(harnessPath, DEBUG);
    
    val timer = new Timer
    timer.start
    if (DEBUG) println("Compiling harness")
    // compile harness against Android library and the *instrumented* app (since the harness may use types from the app, and our instrumentation
    // may have made callbacks public that were previously private/protected)
    // place resulting .class file in the top-level directory for the instrumented app
    val compilerOptions = List("-cp", s".${File.pathSeparator}${androidJarPath}${File.pathSeparator}$instrumentedBinDir", "-d", instrumentedBinDir)
    val compiled = JavaUtil.compile(List(harnessPath), compilerOptions)
    timer.printTimeTaken("Compiling harness")
    assert(compiled, s"Couldn't compile harness $harnessPath")        
    harnessPath
  }          
}
