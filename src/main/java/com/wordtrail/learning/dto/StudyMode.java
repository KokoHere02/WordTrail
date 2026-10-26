package com.wordtrail.learning.dto;

public enum StudyMode {
    RECOGNITION, DICTATION, FOLLOW_TYPING, LISTEN_DICTATION;
    public boolean recallInput() { return this==DICTATION || this==LISTEN_DICTATION; }
}
