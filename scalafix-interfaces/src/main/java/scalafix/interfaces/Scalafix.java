package scalafix.interfaces;

import coursierapi.Repository;
import scalafix.internal.interfaces.ScalafixCoursier;
import scalafix.internal.interfaces.ScalafixInterfacesClassloader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Properties;

/**
 * Public API for reflectively invoking Scalafix from a build tool or IDE integration.
 * <p>
 * To obtain an instance of Scalafix, use one of the static factory methods.
 *
 * @implNote This interface is not intended to be extended, the only implementation of this interface
 * should live in the Scalafix repository.
 */
public interface Scalafix {

    /**
     * @return Construct a new instance of {@link ScalafixArguments}.
     */
    ScalafixArguments newArguments();

    /**
     * Get --help message for running the Scalafix command-line interface.
     *
     * @param screenWidth The width of the screen, used for wrapping long sentences
     *                    into multiple lines.
     * @return The help message as a string.
     */
    String mainHelp(int screenWidth);

    /**
     * The exact Scala versions used
     */
    String scalaVersion();

    /**
     * The release version of the current Scalafix API instance.
     */
    String scalafixVersion();

    /**
     * The recommended Scalameta version to match the current Scalafix API instance.
     */
    String scalametaVersion();

    /**
     * The exact Scala versions that are supported
     */
    String[] supportedScalaVersions();

    @Deprecated
    String scala211();

    /**
     * The Scala 2.12 version in {@link #supportedScalaVersions()}
     */
    String scala212();

    /**
     * The Scala 2.13 version in {@link #supportedScalaVersions()}
     */
    String scala213();

    /**
     * The Scala 3 LTS version in {@link #supportedScalaVersions()}
     */
    String scala3LTS();

    /**
     * The Scala 3 Next version in {@link #supportedScalaVersions()}
     */
    String scala3Next();

    /**
     * Fetch JARs containing an implementation of {@link Scalafix} using Coursier and classload an instance of it via
     * runtime reflection.
     * <p>
     * The custom classloader optionally provided with {@link ScalafixArguments#withToolClasspath} to compile and
     * classload external rules must have the classloader of the returned instance as ancestor to share a common
     * loaded instance of `scalafix-core`, and therefore have been compiled against the requested Scala binary version.
     *
     * @param requestedScalaVersion The Scala version ("3.3.4" for example) available in the classloader of the
     *                              returned instance. To be able to run advanced semantic rules using the Scala
     *                              Presentation Compiler such as ExplicitResultTypes, this must match the version
     *                              that the target classpath was built with, as provided with
     *                              {@link ScalafixArguments#withScalaVersion}.
     * @return An implementation of the {@link Scalafix} interface.
     * @throws ScalafixException in case of errors during artifact resolution/fetching.
     */
    static Scalafix fetchAndClassloadInstance(String scalaBinaryVersion) throws ScalafixException {
        return fetchAndClassloadInstance(scalaBinaryVersion, Repository.defaults());
    }

    /**
     * Fetch JARs containing an implementation of {@link Scalafix} from the provided repositories using Coursier and
     * classload an instance of it via runtime reflection.
     * <p>
     * The custom classloader optionally provided with {@link ScalafixArguments#withToolClasspath} to compile and
     * classload external rules must have the classloader of the returned instance as ancestor to share a common
     * loaded instance of `scalafix-core`, and therefore have been compiled against the requested Scala binary version.
     *
     * @param requestedScalaVersion The Scala version ("3.3.4" for example) available in the classloader of the
     *                              returned instance. To be able to run advanced semantic rules using the Scala
     *                              Presentation Compiler such as ExplicitResultTypes, this must match the version
     *                              that the target classpath was built with, as provided with
     *                              {@link ScalafixArguments#withScalaVersion}.
     * @param repositories       Maven/Ivy repositories to fetch the JARs from.
     * @return An implementation of the {@link Scalafix} interface.
     * @throws ScalafixException in case of errors during artifact resolution/fetching.
     */
    static Scalafix fetchAndClassloadInstance(String requestedScalaVersion, List<Repository> repositories)
            throws ScalafixException {

        String scalaVersionKey;
        if (requestedScalaVersion.startsWith("2.12")) {
            scalaVersionKey = "scala212";
        } else if (requestedScalaVersion.startsWith("2.13")) {
            scalaVersionKey = "scala213";
        } else if (requestedScalaVersion.startsWith("3.0") ||
            requestedScalaVersion.startsWith("3.1") ||
            requestedScalaVersion.startsWith("3.2") ||
            requestedScalaVersion.startsWith("3.3")) {
            scalaVersionKey = "scala3LTS";
        } else if (requestedScalaVersion.startsWith("3")) {
            scalaVersionKey = "scala3Next";
        } else {
            throw new IllegalArgumentException("Unsupported scala version " + requestedScalaVersion);
        }

        Properties properties = new Properties();
        String propertiesPath = "scalafix-interfaces.properties";
        InputStream stream = Scalafix.class.getClassLoader().getResourceAsStream(propertiesPath);
        try {
            properties.load(stream);
        } catch (IOException | NullPointerException e) {
            throw new ScalafixException("Failed to load '" + propertiesPath + "' to lookup versions", e);
        }

        String scalafixVersion = properties.getProperty("scalafixVersion");
        String scalaVersion = properties.getProperty(scalaVersionKey);
        if (scalafixVersion == null || scalaVersion == null)
            throw new ScalafixException("Failed to lookup versions from '" + propertiesPath + "'");

        List<URL> jars = ScalafixCoursier.scalafixCliJars(repositories, scalafixVersion, scalaVersion);
        ClassLoader parent = new ScalafixInterfacesClassloader(Scalafix.class.getClassLoader());
        return classloadInstance(new URLClassLoader(jars.stream().toArray(URL[]::new), parent));
    }

    /**
     * JVM runtime reflection method helper to classload an instance of {@link Scalafix}.
     * <p>
     * The custom classloader optionally provided with {@link ScalafixArguments#withToolClasspath} to compile and
     * classload external rules must have the provided classloader as ancestor to share a common loaded instance
     * of `scalafix-core`, and therefore must have been compiled against the same Scala binary version as
     * the one in the classLoader provided here.
     * <p>
     * Unless you have an advanced use-case, prefer the high-level overloads that cannot cause runtime errors
     * due to an invalid classloader hierarchy.
     *
     * @param classLoader Classloader containing the full Scalafix classpath, including the scalafix-cli module. To be
     *                    able to run advanced semantic rules using the Scala Presentation Compiler such as
     *                    ExplicitResultTypes, this Scala binary version in that classloader should match the one that
     *                    the target classpath was built with, as provided with
     *                    {@link ScalafixArguments#withScalaVersion}.
     * @return An implementation of the {@link Scalafix} interface.
     * @throws ScalafixException in case of errors during classloading, most likely caused
     *                           by an incorrect classloader argument.
     */
    static Scalafix classloadInstance(ClassLoader classLoader) throws ScalafixException {
        try {
            Class<?> cls = classLoader.loadClass("scalafix.internal.interfaces.ScalafixImpl");
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (Scalafix) ctor.newInstance();
        } catch (ClassNotFoundException | NoSuchMethodException |
                IllegalAccessException | InvocationTargetException |
                InstantiationException ex) {
            throw new ScalafixException(
                    "Failed to reflectively load Scalafix with classloader " + classLoader.toString(), ex);
        }
    }
}
