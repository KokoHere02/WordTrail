package com.wordtrail.learning.service;

import com.wordtrail.learning.dto.PronunciationRequest.Accent;
public interface PronunciationProvider {
    byte[] audio(String word,Accent accent);
}
