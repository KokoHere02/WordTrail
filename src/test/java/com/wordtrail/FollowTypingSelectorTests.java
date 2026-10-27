package com.wordtrail;

import com.wordtrail.learning.mapper.FollowTypingMapper.Candidate;
import com.wordtrail.learning.service.FollowTypingSelector;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FollowTypingSelectorTests {
    private final LocalDateTime now=LocalDateTime.of(2026,10,27,12,0);
    private Candidate candidate(long id,Integer result,String verdict,Boolean hinted,LocalDateTime practiced,int selections) {
        return new Candidate(id,"word"+id,null,"词",result,verdict,hinted,practiced,selections);
    }
    @Test void genuineAssessmentsSetWeightsAndCopyingRecencyOnlyReducesRepetition() {
        var difficult=candidate(1,3,null,null,null,0); var familiar=candidate(2,1,"CORRECT",false,null,0);
        assertEquals(6,FollowTypingSelector.weight(difficult,now)/FollowTypingSelector.weight(familiar,now));
        assertTrue(FollowTypingSelector.difficult(candidate(3,1,"INCORRECT",false,null,0)));
        assertTrue(FollowTypingSelector.difficult(candidate(4,1,"CORRECT",true,null,0)));
        assertFalse(FollowTypingSelector.difficult(candidate(5,null,null,null,now,0)));
        assertEquals(0.3,FollowTypingSelector.weight(candidate(6,null,null,null,now.minusHours(1),0),now),0.00001);
        assertEquals(2,FollowTypingSelector.weight(candidate(7,null,null,null,now.minusHours(6),0),now));
    }
    @Test void weightedSamplingCapsDifficultWordsDeduplicatesAndNeverSelectsCappedWords() {
        var pool=new ArrayList<Candidate>();
        for(int i=0;i<90;i++) {
            Integer result=null; if(i<30) result=3; else if(i>=60) result=1;
            pool.add(candidate(i,result,i>=60?"CORRECT":null,false,null,0));
        }
        pool.add(pool.getFirst()); pool.add(candidate(999,3,null,null,null,2));
        var random=new Random(109); long difficult=0,newWords=0,familiar=0;
        for(int round=0;round<500;round++) {
            var chosen=FollowTypingSelector.select(pool,20,now,random);
            assertEquals(20,chosen.size()); assertEquals(20,chosen.stream().map(Candidate::wordId).distinct().count());
            assertTrue(chosen.stream().filter(FollowTypingSelector::difficult).count()<=8);
            assertTrue(chosen.stream().noneMatch(c->c.wordId()==999));
            difficult+=chosen.stream().filter(FollowTypingSelector::difficult).count();
            newWords+=chosen.stream().filter(c->c.recognitionResult()==null).count();
            familiar+=chosen.stream().filter(c->Objects.equals(c.recognitionResult(),1)).count();
        }
        assertTrue(difficult>2*familiar); assertTrue(newWords>2*familiar); assertTrue(familiar>0);
    }
    @Test void shortagesReturnSmallerRoundsWithoutFillingDifficultyOrRepetitionLimits() {
        var pool=new ArrayList<Candidate>();
        for(int i=0;i<30;i++) pool.add(candidate(i,3,null,null,null,0));
        assertEquals(4,FollowTypingSelector.select(pool,10,now,new Random(1)).size());
        assertEquals(8,FollowTypingSelector.select(pool,20,now,new Random(1)).size());
        assertTrue(FollowTypingSelector.select(List.of(candidate(1,3,null,null,null,2)),20,now,new Random(1)).isEmpty());
    }
}
