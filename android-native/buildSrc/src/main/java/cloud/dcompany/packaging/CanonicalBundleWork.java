package cloud.dcompany.packaging;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/** Tools are isolated so their AGP plugin marker never shadows the app plugin. */
public abstract class CanonicalBundleWork implements WorkAction<CanonicalBundleWork.Parameters> {
    public interface Parameters extends WorkParameters {
        RegularFileProperty getRawBundle();
        RegularFileProperty getApkFile();
        RegularFileProperty getCanonicalBundle();
        DirectoryProperty getAuditDirectory();
    }
    @Override public void execute() {
        Parameters p = getParameters();
        try {
            CanonicalBundle.write(p.getRawBundle().get().getAsFile().toPath(), p.getApkFile().get().getAsFile().toPath(),
                    p.getCanonicalBundle().get().getAsFile().toPath(), p.getAuditDirectory().get().getAsFile().toPath());
        } catch (Exception failure) {
            throw new IllegalStateException("Unsigned bundle canonicalization failed", failure);
        }
    }
}
