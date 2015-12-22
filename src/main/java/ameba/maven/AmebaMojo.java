package ameba.maven;

import ameba.core.Application;
import ameba.dev.Enhancing;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.classloading.enhancers.EnhancingException;
import ameba.util.ClassUtils;
import com.google.common.collect.Sets;
import javassist.ClassPool;
import javassist.CtClass;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @goal enhance
 * @requiresProject true
 * @requiresDependencyResolution compile
 * @phase process-classes
 * @execute phase="process-classes"
 */
public class AmebaMojo extends AbstractMojo {
    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     */
    private MavenProject project;
    /**
     * Set the directory holding the class files we want to transform.
     *
     * @parameter default-value="${project.build.outputDirectory}"
     */
    private String classSource;
    /**
     * Set the application config ids.
     *
     * @parameter
     */
    private String[] ids;
    /**
     * Set the read application config file.
     *
     * @parameter
     */
    private boolean readAppConf = false;
    private ReloadClassLoader classLoader;

    @SuppressWarnings("unchecked")
    public void execute()
            throws MojoExecutionException {
        final Log log = getLog();
        if (classSource == null) {
            classSource = project.getBuild().getOutputDirectory();
        }

        log.info("Enhancing classes ...");

        File f = new File("");
        log.info("Current Directory: " + f.getAbsolutePath());
        ClassLoader oldClassLoader = ClassUtils.getContextClassLoader();
        classLoader = buildClassLoader(new File(project.getBuild().getSourceDirectory()).getAbsoluteFile());
        Thread.currentThread().setContextClassLoader(classLoader);

        Properties properties = Application.readDefaultConfig();
        if (readAppConf) {
            Application.readAppConfig(properties, Application.DEFAULT_APP_CONF);
            if (ids != null && ids.length > 0) {
                Set<String> configFiles = Application.parseIds2ConfigFile(ids);
                for (String conf : configFiles) {
                    Application.readAppConfig(properties, conf);
                }
            }
        }

        Application.readModuleConfig(properties, false);

        String encoding = (String) project.getProperties().get("project.build.sourceEncoding");
        if (StringUtils.isBlank(encoding)) encoding = "utf-8";
        properties.setProperty("app.encoding", encoding);
        properties.setProperty("ebean.enhancer.log.level", "0");

        Enhancing.loadEnhancers((Map) properties);

        process("", true);
        Thread.currentThread().setContextClassLoader(oldClassLoader);
    }

    private void process(String dir, boolean recurse) {

        String dirPath = classSource + "/" + dir;
        File d = new File(dirPath);
        if (!d.exists()) {
            File currentDir = new File(".");
            String m = "File not found " + dirPath + "  currentDir:" + currentDir.getAbsolutePath();
            throw new RuntimeException(m);
        }

        File[] files = d.listFiles();
        File f = null;
        try {
            for (File file : files) {
                f = file;
                if (file.isDirectory()) {
                    if (recurse) {
                        String subdir = dir + "/" + file.getName();
                        process(subdir, recurse);
                    }
                } else {
                    String fileName = file.getName();
                    if (fileName.endsWith(".java")) {
                        // possibly a common mistake... mixing .java and .class
                        getLog().debug("Expecting a .class file but got " + fileName + " ... ignoring");

                    } else if (fileName.endsWith(".class")) {
                        String name = file.getPath().substring(classSource.length());
                        transformFile(name);
                    }
                }
            }

        } catch (Exception e) {
            String fileName = f == null ? "null" : f.getName();
            String m = "Error transforming file " + fileName;
            throw new RuntimeException(m, e);
        }

    }

    private void transformFile(String file) {
        if (file.startsWith(File.separator)) {
            file = file.substring(1);
        }
        String name = file.replace(File.separator, ".");
        name = name.substring(0, name.length() - 6);
        ClassDescription desc = classLoader.getClassCache().get(name);
        enhance(desc);
    }

    private void enhance(ClassDescription desc) {
        if (desc == null) return;
        ClassPool classPool = Enhancer.getClassPool();
        CtClass clazz;
        try {
            clazz = classPool.makeClass(desc.getEnhancedByteCodeStream());
        } catch (IOException e) {
            throw new EnhancingException(e);
        }
        if (clazz.isInterface()
                || clazz.getName().endsWith(".package")
                || clazz.isEnum()
                || clazz.isFrozen()
                || clazz.isPrimitive()
                || clazz.isAnnotation()
                || clazz.isArray()) {
            return;
        }
        for (Enhancer enhancer : Enhancing.getEnhancers()) {
            enhance(enhancer, desc);
            try {
                FileUtils.writeByteArrayToFile(desc.classFile.getAbsoluteFile(), desc.enhancedByteCode);
            } catch (IOException e) {
                getLog().error(e);
            }
        }
    }

    private void enhance(Enhancer enhancer, ClassDescription desc) {
        try {
            long start = System.currentTimeMillis();
            enhancer.enhance(desc);
            getLog().debug(
                    System.currentTimeMillis() - start + "ms to apply "
                            + enhancer.getClass().getSimpleName()
                            + "[version: " + enhancer.getVersion() + "] to " + desc.className);
        } catch (Exception e) {
            throw new EnhancingException("While applying " + enhancer + " on " + desc.className, e);
        }
    }

    private ReloadClassLoader buildClassLoader(File pkgRoot) {

        URL[] urls = buildClassPath();
        ClassLoader loader = URLClassLoader.newInstance(urls, Thread.currentThread().getContextClassLoader());
        return new MojoClassLoader(loader, pkgRoot);
    }

    private URL[] buildClassPath() {
        try {
            Set<URL> urls = Sets.newLinkedHashSet();

            URL projectOut = new File(project.getBuild().getOutputDirectory()).toURI().toURL();
            urls.add(projectOut);

            Set<Artifact> artifacts = project.getArtifacts();

            for (Artifact a : artifacts) {
                urls.add(a.getFile().toURI().toURL());
            }

            getLog().debug("ClassPath URLs: " + urls);

            return urls.toArray(new URL[urls.size()]);

        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static class MavenApp extends Application {
        public MavenApp() {
        }

        @Override
        protected void addOnSetup(Map<String, Object> configMap) {
            super.addOnSetup(configMap);
        }
    }

    private class MojoClassLoader extends ReloadClassLoader {

        protected MojoClassLoader(ClassLoader parent, File pkgRoot) {
            super(parent, pkgRoot);
        }

        @Override
        protected void enhanceClass(ClassDescription desc) {
            enhance(desc);
        }
    }
}
