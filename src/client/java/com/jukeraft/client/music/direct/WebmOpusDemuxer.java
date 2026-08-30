package com.jukeraft.client.music.direct;

import java.util.ArrayList;
import java.util.List;

final class WebmOpusDemuxer {
    private static final long ID_SEGMENT = 0x18538067L;
    private static final long ID_CLUSTER = 0x1F43B675L;
    private static final long ID_SIMPLE_BLOCK = 0xA3L;
    private static final long ID_BLOCK_GROUP = 0xA0L;
    private static final long ID_BLOCK = 0xA1L;
    private static final long ID_TRACKS = 0x1654AE6BL;
    private static final long ID_INFO = 0x1549A966L;
    private static final long ID_TIMECODE_SCALE = 0x2AD7B1L;
    private static final long ID_CUES = 0x1C53BB6BL;
    private static final long ID_CUE_POINT = 0xBBL;
    private static final long ID_CUE_TIME = 0xB3L;
    private static final long ID_CUE_TRACK_POSITIONS = 0xB7L;
    private static final long ID_CUE_CLUSTER_POSITION = 0xF1L;

    record CuePoint(double timeSeconds, long byteOffset) {
    }

    private byte[] data = new byte[0];
    private int pos;
    private int emittedCount;
    private long segmentDataStart = -1;
    private long timecodeScale = 1_000_000L;
    private List<CuePoint> cuePoints = List.of();

    List<byte[]> feed(byte[] chunk) {
        byte[] grown = new byte[data.length + chunk.length];
        System.arraycopy(data, 0, grown, 0, data.length);
        System.arraycopy(chunk, 0, grown, data.length, chunk.length);
        data = grown;

        List<byte[]> all = new ArrayList<>();
        List<CuePoint> cues = new ArrayList<>();
        pos = 0;
        try {
            walk(data.length, all, cues);
        } catch (ArrayIndexOutOfBoundsException incompleteTail) {

        }
        if (!cues.isEmpty()) {
            cuePoints = cues;
        }

        if (emittedCount >= all.size()) {
            return List.of();
        }
        List<byte[]> fresh = all.subList(emittedCount, all.size());
        emittedCount = all.size();
        return fresh;
    }

    List<CuePoint> getCuePoints() {
        return cuePoints;
    }

    private void walk(int end, List<byte[]> out, List<CuePoint> cues) {
        while (pos < end) {
            long id = readId();
            long size = readSize();
            int elementEnd = (int) Math.min(end, pos + size);

            if (id == ID_SEGMENT) {
                if (segmentDataStart < 0) {
                    segmentDataStart = pos;
                }
                walk(elementEnd, out, cues);
            } else if (id == ID_CLUSTER || id == ID_TRACKS || id == ID_BLOCK_GROUP) {
                walk(elementEnd, out, cues);
            } else if (id == ID_INFO) {
                walkInfo(elementEnd);
            } else if (id == ID_CUES) {
                walkCues(elementEnd, cues);
            } else if (id == ID_SIMPLE_BLOCK || id == ID_BLOCK) {
                byte[] packet = readBlockPayload(elementEnd);
                if (packet != null) {
                    out.add(packet);
                }
                pos = elementEnd;
            } else {
                pos = elementEnd;
            }
        }
    }

    private void walkInfo(int end) {
        while (pos < end) {
            long id = readId();
            long size = readSize();
            int elementEnd = (int) Math.min(end, pos + size);
            if (id == ID_TIMECODE_SCALE) {
                timecodeScale = readUint(elementEnd);
            }
            pos = elementEnd;
        }
    }

    private void walkCues(int end, List<CuePoint> cues) {
        while (pos < end) {
            long id = readId();
            long size = readSize();
            int elementEnd = (int) Math.min(end, pos + size);
            if (id == ID_CUE_POINT) {
                walkCuePoint(elementEnd, cues);
            } else {
                pos = elementEnd;
            }
        }
    }

    private void walkCuePoint(int end, List<CuePoint> cues) {
        long cueTime = -1;
        long clusterPos = -1;
        while (pos < end) {
            long id = readId();
            long size = readSize();
            int elementEnd = (int) Math.min(end, pos + size);
            if (id == ID_CUE_TIME) {
                cueTime = readUint(elementEnd);
                pos = elementEnd;
            } else if (id == ID_CUE_TRACK_POSITIONS) {
                long found = walkCueTrackPositions(elementEnd);
                if (found >= 0) {
                    clusterPos = found;
                }
            } else {
                pos = elementEnd;
            }
        }
        if (cueTime >= 0 && clusterPos >= 0 && segmentDataStart >= 0) {
            double seconds = cueTime * (timecodeScale / 1_000_000_000.0);
            cues.add(new CuePoint(seconds, segmentDataStart + clusterPos));
        }
    }

    private long walkCueTrackPositions(int end) {
        long clusterPos = -1;
        while (pos < end) {
            long id = readId();
            long size = readSize();
            int elementEnd = (int) Math.min(end, pos + size);
            if (id == ID_CUE_CLUSTER_POSITION) {
                clusterPos = readUint(elementEnd);
            }
            pos = elementEnd;
        }
        return clusterPos;
    }

    private long readUint(int elementEnd) {
        long value = 0;
        while (pos < elementEnd) {
            value = (value << 8) | (data[pos++] & 0xFFL);
        }
        return value;
    }

    private byte[] readBlockPayload(int elementEnd) {
        readVint();
        pos += 2;
        int flags = data[pos++] & 0xFF;
        int lacing = (flags >> 1) & 0x3;
        if (lacing != 0) {

            return null;
        }
        int len = elementEnd - pos;
        if (len <= 0) {
            return null;
        }
        byte[] frame = new byte[len];
        System.arraycopy(data, pos, frame, 0, len);
        pos = elementEnd;
        return frame;
    }

    private long readId() {
        int first = data[pos] & 0xFF;
        int length = vintLength(first);
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[pos + i] & 0xFFL);
        }
        pos += length;
        return value;
    }

    private long readSize() {
        int first = data[pos] & 0xFF;
        int length = vintLength(first);
        long mask = 0xFFL >>> length;
        long value = data[pos] & mask;
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[pos + i] & 0xFFL);
        }
        pos += length;
        return value;
    }

    private long readVint() {
        return readId();
    }

    private static int vintLength(int firstByte) {
        if (firstByte == 0) {
            return 8;
        }
        int length = 1;
        int mask = 0x80;
        while ((firstByte & mask) == 0) {
            mask >>= 1;
            length++;
        }
        return length;
    }
}
