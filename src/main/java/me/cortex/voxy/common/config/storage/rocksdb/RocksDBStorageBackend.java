package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

public class RocksDBStorageBackend extends StorageBackend {
    private final RocksDB db;
    private final ColumnFamilyHandle worldSections;
    private final ColumnFamilyHandle idMappings;
    private final ReadOptions sectionReadOps;
    private final WriteOptions sectionWriteOps;

    //NOTE: closes in order
    private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();

    public RocksDBStorageBackend(String path) {
        RocksDB.loadLibrary();

        OpenedDatabase opened;
        try {
            opened = openDatabase(path);
        } catch (RocksDBException e) {
            if (!isRecoverableCorruption(e)) {
                throw new RuntimeException(e);
            }

            Logger.error("Corrupted Voxy LOD database at " + path + ". Backing up and creating a fresh database.");
            Logger.error("LOD data for this world will be rebuilt as you explore. Cause: " + e.getMessage());

            try {
                backupCorruptedStorage(path);
                opened = openDatabase(path);
            } catch (RocksDBException | IOException recoveryFailure) {
                throw new RuntimeException("Failed to recover corrupted Voxy database at " + path, recoveryFailure);
            }
        }

        this.db = opened.db;
        this.worldSections = opened.worldSections;
        this.idMappings = opened.idMappings;
        this.sectionReadOps = opened.sectionReadOps;
        this.sectionWriteOps = opened.sectionWriteOps;
        this.closeList.addAll(opened.closeList);
    }

    private record OpenedDatabase(
            RocksDB db,
            ColumnFamilyHandle worldSections,
            ColumnFamilyHandle idMappings,
            ReadOptions sectionReadOps,
            WriteOptions sectionWriteOps,
            List<AbstractImmutableNativeReference> closeList
    ) {}

    private static OpenedDatabase openDatabase(String path) throws RocksDBException {
        //TODO: FIXME: DONT USE THE SAME options PER COLUMN FAMILY
        final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                .optimizeForSmallDb();

        final ColumnFamilyOptions cfWorldSecOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.NO_COMPRESSION)
                .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                .setLevelCompactionDynamicLevelBytes(true)
                .optimizeForPointLookup(128);

        var bCache = new HyperClockCache(128*1024L*1024L,0, 4, false);
        var filter = new BloomFilter(10);
        cfWorldSecOpts.setTableFormatConfig(new BlockBasedTableConfig()
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setBlockCache(bCache)
                .setDataBlockHashTableUtilRatio(0.75)
                //.setIndexType(IndexType.kHashSearch)//Maybe?
                .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
                .setFilterPolicy(filter)
        );

        final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
            new ColumnFamilyDescriptor("world_sections".getBytes(), cfWorldSecOpts),
            new ColumnFamilyDescriptor("id_mappings".getBytes(), cfOpts)
        );

        final DBOptions options = new DBOptions()
                //.setUnorderedWrite(true)
                .setAvoidUnnecessaryBlockingIO(true)
                .setIncreaseParallelism(2)
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxTotalWalSize(1024*1024*128);//128 mb max WAL size

        List<ColumnFamilyHandle> handles = new ArrayList<>();

        RocksDB db = RocksDB.open(options, path, cfDescriptors, handles);

        ReadOptions sectionReadOps = new ReadOptions();
        WriteOptions sectionWriteOps = new WriteOptions();

        List<AbstractImmutableNativeReference> closeList = new ArrayList<>();
        closeList.addAll(handles);
        closeList.add(db);
        closeList.add(options);
        closeList.add(cfOpts);
        closeList.add(cfWorldSecOpts);
        closeList.add(sectionReadOps);
        closeList.add(sectionWriteOps);
        closeList.add(filter);
        closeList.add(bCache);

        ColumnFamilyHandle worldSections = handles.get(1);
        ColumnFamilyHandle idMappings = handles.get(2);

        db.flushWal(true);

        return new OpenedDatabase(db, worldSections, idMappings, sectionReadOps, sectionWriteOps, closeList);
    }

    private static boolean isRecoverableCorruption(RocksDBException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("corruption")
                || lower.contains("corrupted")
                || (lower.contains("no such file") && (lower.contains(".sst") || lower.contains("manifest")));
    }

    private static void backupCorruptedStorage(String path) throws IOException {
        Path storagePath = Path.of(path);
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
            return;
        }

        Path backupPath = storagePath.resolveSibling(storagePath.getFileName().toString() + ".corrupted." + System.currentTimeMillis());
        Files.move(storagePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        Logger.info("Backed up corrupted Voxy database to " + backupPath);
        Files.createDirectories(storagePath);
    }

    @Override
    public void iterateStoredSectionPositions(LongConsumer consumer) {
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer keyBuff = stack.calloc(8);
            long keyBuffPtr = MemoryUtil.memAddress(keyBuff);
            var iter = this.db.newIterator(this.worldSections, this.sectionReadOps);
            iter.seekToFirst();
            while (iter.isValid()) {
                iter.key(keyBuff);
                long key = Long.reverseBytes(MemoryUtil.memGetLong(keyBuffPtr));
                consumer.accept(key);
                iter.next();
            }
            iter.close();
        }
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        try (var stack = MemoryStack.stackPush()){
            var buffer = stack.malloc(8);
            //HATE JAVA HATE JAVA HATE JAVA, Long.reverseBytes()
            //THIS WILL ONLY WORK ON LITTLE ENDIAN SYSTEM AAAAAAAAA ;-;

            MemoryUtil.memPutLong(MemoryUtil.memAddress(buffer), Long.reverseBytes(swizzlePos(key)));

            var result = this.db.get(this.worldSections,
                    this.sectionReadOps,
                    buffer,
                    MemoryUtil.memByteBuffer(scratch.address, (int) (scratch.size)));

            if (result == RocksDB.NOT_FOUND) {
                return null;
            }

            return scratch.subSize(result);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    //TODO: FIXME, use the ByteBuffer variant
    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        try (var stack = MemoryStack.stackPush()) {
            var keyBuff = stack.calloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(swizzlePos(key)));
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteSectionData(long key) {
        try {
            this.db.delete(this.worldSections, longToBytes(swizzlePos(key)));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        try {
            var buffer = new byte[data.remaining()];
            data.get(buffer);
            data.rewind();
            this.db.put(this.idMappings, intToBytes(id), buffer);
        } catch (
                RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var iterator = this.db.newIterator(this.idMappings);
        var out = new Int2ObjectOpenHashMap<byte[]>();
        for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            out.put(bytesToInt(iterator.key()), iterator.value());
        }
        return out;
    }

    @Override
    public void flush() {
        try {
            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        this.flush();
        this.closeList.forEach(AbstractImmutableNativeReference::close);
    }

    private static byte[] intToBytes(int i) {
        return new byte[] {(byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte) i};
    }
    private static int bytesToInt(byte[] i) {
        return (Byte.toUnsignedInt(i[0])<<24)|(Byte.toUnsignedInt(i[1])<<16)|(Byte.toUnsignedInt(i[2])<<8)|(Byte.toUnsignedInt(i[3]));
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }

    private static long bytesToLong(final byte[] b) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new RocksDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
        }

        public static String getConfigTypeName() {
            return "RocksDB";
        }
    }

    private static long swizzlePos(long key) {
        if (true) {
            return key;
        }
        if (WorldEngine.POS_FORMAT_VERSION != 1) throw new IllegalStateException("TODO: UPDATE THIS");
        return  (key&(0xFL<<60)) |
                Long.expand((key>>> 4)&((1L<<24)-1), 0b01010101010101010101010101010101_001001001001001001001001L) |
                Long.expand((key>>>52)&0xFF,         0b00000000000000000000000000000000_100100100100100100100100L) |
                Long.expand((key>>>28)&((1L<<24)-1), 0b10101010101010101010101010101010_010010010010010010010010L);
    }
}
