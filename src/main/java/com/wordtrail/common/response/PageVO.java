package com.wordtrail.common.response;

import java.util.List;

public record PageVO<T>(long page, long size, long total, List<T> records) {}
