package com.wordtrail.learning.service;

import com.wordtrail.learning.mapper.FollowTypingMapper.Candidate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.random.RandomGenerator;

/** Weighted sampling without replacement; copying correctness never acts as an assessment. */
public final class FollowTypingSelector {
    private FollowTypingSelector() {}
    public static boolean difficult(Candidate c) {
        return Objects.equals(c.recognitionResult(),3) || c.recallVerdict()!=null
            && (!"CORRECT".equals(c.recallVerdict()) || Boolean.TRUE.equals(c.recallHintUsed()));
    }
    public static double weight(Candidate c,LocalDateTime now) {
        double base=difficult(c)?3.0:c.recognitionResult()==null && c.recallVerdict()==null?2.0:
            Objects.equals(c.recognitionResult(),1) && "CORRECT".equals(c.recallVerdict()) && !Boolean.TRUE.equals(c.recallHintUsed())?0.5:1.5;
        return c.lastPracticedAt()!=null && c.lastPracticedAt().isAfter(now.minusHours(6))?base*0.15:base;
    }
    public static List<Candidate> select(List<Candidate> pool,int count,LocalDateTime now,RandomGenerator random) {
        record Ranked(Candidate candidate,double score) {}
        var ranked=pool.stream().filter(c->c.recentSelectionCount()<2)
            .map(c->new Ranked(c,-Math.log(Math.max(Double.MIN_VALUE,random.nextDouble()))/weight(c,now)))
            .sorted(Comparator.comparingDouble(Ranked::score)).toList();
        var selected=new ArrayList<Candidate>(); var seen=new HashSet<Long>(); int difficultCount=0,cap=count*2/5;
        for(var value:ranked) {
            var c=value.candidate(); boolean difficult=difficult(c);
            if(difficult && difficultCount>=cap || !seen.add(c.wordId())) continue;
            selected.add(c); if(difficult) difficultCount++;
            if(selected.size()==count) break;
        }
        return selected;
    }
}
