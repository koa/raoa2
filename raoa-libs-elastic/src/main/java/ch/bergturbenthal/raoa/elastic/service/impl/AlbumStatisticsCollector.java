package ch.bergturbenthal.raoa.elastic.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.model.KeywordCount;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import lombok.Getter;
import org.eclipse.jgit.lib.ObjectId;

public class AlbumStatisticsCollector {
  private final LongSummaryStatistics timeSummary = new LongSummaryStatistics();
  private final LongAdder entryCount = new LongAdder();
  @Getter private final Set<ObjectId> remainingEntries;
  private final Map<String, AtomicInteger> keywordCounts = new HashMap<>();

  public AlbumStatisticsCollector(final Set<ObjectId> remainingEntries) {
    this.remainingEntries = new HashSet<>(remainingEntries);
  }

  public synchronized void addAlbumData(AlbumEntryData entry) {
    remainingEntries.remove(entry.getEntryId());
    if (entry.getCreateTime() != null) {
      timeSummary.accept(entry.getCreateTime().getEpochSecond());
    }
    entryCount.increment();
    final Set<String> keywords = entry.getKeywords();
    if (keywords != null) {
      for (String keyword : keywords)
        keywordCounts.computeIfAbsent(keyword, k -> new AtomicInteger()).incrementAndGet();
    }
  }

  public synchronized AlbumData.AlbumDataBuilder fill(AlbumData.AlbumDataBuilder target) {
    if (timeSummary.getCount() > 0)
      target.createTime(Instant.ofEpochSecond((long) timeSummary.getAverage()));
    target.entryCount(entryCount.intValue());
    target.keywordCount(
        keywordCounts.entrySet().stream()
            .map(
                e ->
                    KeywordCount.builder()
                        .keyword(e.getKey())
                        .entryCount(e.getValue().get())
                        .build())
            .collect(Collectors.toList()));
    return target;
  }
}
