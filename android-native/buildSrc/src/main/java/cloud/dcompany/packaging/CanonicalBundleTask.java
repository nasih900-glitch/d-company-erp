package cloud.dcompany.packaging;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import javax.inject.Inject;
import org.gradle.workers.WorkerExecutor;
import org.gradle.api.tasks.*;

/** Public BUNDLE transform. The compiler's same-variant APK defines DEX names. */
public abstract class CanonicalBundleTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRawBundle();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDirectory();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApkFile();
    @Classpath public abstract ConfigurableFileCollection getProfileTools();
    @Inject protected abstract WorkerExecutor getWorkerExecutor();
    @OutputFile public abstract RegularFileProperty getCanonicalBundle();
    @OutputDirectory public abstract DirectoryProperty getAuditDirectory();

    @TaskAction public void transform() throws Exception {
        Path directory = getApkDirectory().get().getAsFile().toPath().toRealPath();
        Path apk = getApkFile().get().getAsFile().toPath();
        if (Files.isSymbolicLink(apk) || !Files.isRegularFile(apk) || !apk.toRealPath().startsWith(directory)) {
            throw new IllegalArgumentException("Compiler APK must be regular and inside the variant artifact directory");
        }
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProfileTools()))
                .submit(CanonicalBundleWork.class, parameters -> {
                    parameters.getRawBundle().set(getRawBundle());
                    parameters.getApkFile().set(getApkFile());
                    parameters.getCanonicalBundle().set(getCanonicalBundle());
                    parameters.getAuditDirectory().set(getAuditDirectory());
                });
    }
}
