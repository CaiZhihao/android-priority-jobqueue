package com.path.android.jobqueue;

import java.util.Set;

public enum TagConstraint {
    ALL,
    ANY;
    public boolean matches(String[] constraintTags, Set<String> jobTags) {
        if (this == TagConstraint.ANY) {
            for (String tag : constraintTags) {
                if (jobTags.contains(tag)) {
                    return true;
                }
            }
            return false;
        } else {
            for (String tag : constraintTags) {
                if (!jobTags.contains(tag)) {
                    return false;
                }
            }
            return true;
        }
    }
}
