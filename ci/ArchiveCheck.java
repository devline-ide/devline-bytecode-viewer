import java.nio.file.Path;

/** Uses the shipped host parser rather than a second, weaker ZIP validator. */
class ArchiveCheck {
    public static void main(String[] args) {
        var archive = devline.extensions.ExtensionArchive.Companion.read(Path.of(args[0]));
        var descriptor = archive.getDescriptor();
        devline.extensions.IdeExtensionHost.Companion.getCurrent().assess(descriptor).requireCompatible();
        System.out.println("ARCHIVE_HOST_VALIDATED " + descriptor.getExtension().getId() + " " +
            descriptor.getExtension().getVersion() + " " + archive.getChecksum());
    }
}
