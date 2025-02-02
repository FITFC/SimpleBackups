package de.melanx.simplebackups;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nonnull;

public class BackupData extends SavedData {

    private long lastSaved;
    private boolean paused;

    private BackupData() {
        // use BackupData.get
    }

    public static BackupData get(ServerLevel level) {
        return BackupData.get(level.getServer());
    }

    public static BackupData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(nbt -> new BackupData().load(nbt), BackupData::new, "simplebackups");
    }

    public BackupData load(@Nonnull CompoundTag nbt) {
        this.lastSaved = nbt.getLong("lastSaved");
        this.paused = nbt.getBoolean("paused");
        return this;
    }

    @Nonnull
    @Override
    public CompoundTag save(@Nonnull CompoundTag nbt) {
        nbt.putLong("lastSaved", this.lastSaved);
        nbt.putBoolean("paused", this.paused);
        return nbt;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        this.setDirty();
    }

    public boolean isPaused() {
        return this.paused;
    }

    public long getLastSaved() {
        return this.lastSaved;
    }

    public void updateSaveTime(long time) {
        this.lastSaved = time;
        this.setDirty();
    }
}
