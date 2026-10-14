package com.repopulse.hotspots;
import java.time.Instant;
public record Hotspot(String path,long touchCount,long additions,long deletions,long bugFixMessageMatches,Instant lastChangedAt) {}
