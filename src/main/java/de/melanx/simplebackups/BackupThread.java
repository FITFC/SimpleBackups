package de.melanx.simplebackups;

import net.minecraft.ChatFormatting;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.FileUtil;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupThread extends Thread {

    private static final Path OUTPUT_PATH = FMLPaths.GAMEDIR.get().resolve("simplebackups");
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD).appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2).appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2).appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral('-')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral('-')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();
    public static final Logger LOGGER = LogManager.getLogger(BackupThread.class);
    private final MinecraftServer server;
    private final LevelStorageSource.LevelStorageAccess storageSource;

    private BackupThread(@Nonnull MinecraftServer server) {
        this.server = server;
        this.storageSource = server.storageSource;
        this.setName("SimpleBackups");
        this.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
    }

    public static boolean tryCreateBackup(MinecraftServer server) {
        BackupThread thread = new BackupThread(server);
        BackupData backupData = BackupData.get(server);
        if (!server.getPlayerList().getPlayers().isEmpty() && System.currentTimeMillis() - ConfigHandler.getTimer() > backupData.getLastSaved()) {
            File backups = OUTPUT_PATH.toFile();
            if (backups.isDirectory()) {
                File[] files = backups.listFiles();
                if (files != null && files.length >= ConfigHandler.getBackupsToKeep()) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                    while (files.length >= ConfigHandler.getBackupsToKeep()) {
                        boolean deleted = files[0].delete();
                        String name = files[0].getName();
                        if (deleted) {
                            LOGGER.info("Successfully deleted \"" + name + "\"");
                            files = Arrays.copyOfRange(files, 1, files.length);
                        }
                    }
                }
            }

            thread.start();
            backupData.updateSaveTime(System.currentTimeMillis());
            return true;
        }

        return false;
    }

    public static void saveStorageSize() {
        try {
            while (BackupThread.getOutputFolderSize() > ConfigHandler.getMaxDiskSize()) {
                File[] files = OUTPUT_PATH.toFile().listFiles();
                if (Objects.requireNonNull(files).length == 1) {
                    LOGGER.error("Cannot delete old files to save disk space. Only one backup file left!");
                    return;
                }

                Arrays.sort(Objects.requireNonNull(files), Comparator.comparingLong(File::lastModified));
                File file = files[0];
                String name = file.getName();
                if (file.delete()) {
                    LOGGER.info("Successfully deleted \"" + name + "\"");
                }
            }
        } catch (NullPointerException | IOException e) {
            LOGGER.error("Cannot delete old files to save disk space", e);
        }
    }

    @Override
    public void run() {
        try {
            Files.createDirectories(OUTPUT_PATH);
            long start = System.currentTimeMillis();
            this.broadcast("simplebackups.backup_started", Style.EMPTY.withColor(ChatFormatting.GOLD));
            long size = this.makeWorldBackup();
            long end = System.currentTimeMillis();
            String time = Timer.getTimer(end - start);
            BackupThread.saveStorageSize();
            this.broadcast("simplebackups.backup_finished", Style.EMPTY.withColor(ChatFormatting.GOLD), time, StorageSize.getFormattedSize(size), StorageSize.getFormattedSize(BackupThread.getOutputFolderSize()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static long getOutputFolderSize() throws IOException {
        File[] files = OUTPUT_PATH.toFile().listFiles();
        long size = 0;
        try {
            for (File file : Objects.requireNonNull(files)) {
                size += Files.size(file.toPath());
            }
        } catch (NullPointerException e) {
            return 0;
        }

        return size;
    }

    private void broadcast(String message, Style style, Object... parameters) {
        Component defaultComponent = new TextComponent(DefaultTranslator.parseKey(message, parameters)).withStyle(style);
        Component realComponent = new TranslatableComponent(message, parameters).withStyle(style);

        this.server.getPlayerList().getPlayers().forEach(player -> {
            if (DefaultTranslator.PLAYERS_WITHOUT_MOD.contains(player.getGameProfile().getId())) {
                player.sendMessage(defaultComponent, Util.NIL_UUID);
            } else {
                player.sendMessage(realComponent, Util.NIL_UUID);
            }
        });
    }

    // vanilla copy with modifications
    private long makeWorldBackup() throws IOException {
        this.storageSource.checkLock();
        String fileName = this.storageSource.levelId + "_" + LocalDateTime.now().format(FORMATTER);
        Path path = FMLPaths.GAMEDIR.get().resolve("simplebackups");

        try {
            Files.createDirectories(Files.exists(path) ? path.toRealPath() : path);
        } catch (IOException ioexception) {
            throw new RuntimeException(ioexception);
        }

        Path outputFile = path.resolve(FileUtil.findAvailableName(path, fileName, ".zip"));
        final ZipOutputStream zipStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputFile)));

        try {
            Path levelName = Paths.get(this.storageSource.levelId);
            Path levelPath = this.storageSource.levelPath;
            Files.walkFileTree(levelPath, new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.endsWith("session.lock")) {
                        String completePath = levelName.resolve(levelPath.relativize(file)).toString().replace('\\', '/');
                        ZipEntry zipentry = new ZipEntry(completePath);
                        zipStream.putNextEntry(zipentry);
                        com.google.common.io.Files.asByteSource(file.toFile()).copyTo(zipStream);
                        zipStream.closeEntry();
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            try {
                zipStream.close();
            } catch (IOException e1) {
                e.addSuppressed(e1);
            }

            throw e;
        }

        zipStream.close();
        return Files.size(outputFile);
    }

    private static class Timer {

        private static final SimpleDateFormat SECONDS = new SimpleDateFormat("s.SSS");
        private static final SimpleDateFormat MINUTES = new SimpleDateFormat("mm:ss");
        private static final SimpleDateFormat HOURS = new SimpleDateFormat("HH:mm");

        public static String getTimer(long milliseconds) {
            Date date = new Date(milliseconds);
            double seconds = milliseconds / 1000d;
            if (seconds < 60) {
                return SECONDS.format(date) + "s";
            } else if (seconds < 3600) {
                return MINUTES.format(date) + "min";
            } else {
                return HOURS.format(date) + "h";
            }
        }
    }
}