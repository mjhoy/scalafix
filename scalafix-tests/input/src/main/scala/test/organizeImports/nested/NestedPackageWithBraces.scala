/*
rules = [OrganizeImports]
OrganizeImports.removeUnused = false
 */
package test.organizeImports {
  package nested {
    import java.time.Clock
    import scala.collection.JavaConverters._
    import com.sun.management.DiagnosticCommandMBean
    import scala.concurrent.ExecutionContext
    import javax.net.ssl

    object NestedPackageWithBraces
  }
}
