/*
 *  Copyright (C) 2010-2012 Stichting Akvo (Akvo Foundation)
 *
 *  This file is part of Akvo FLOW.
 *
 *  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
 *  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
 *  either version 3 of the License or any later version.
 *
 *  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Affero General Public License included below for more details.
 *
 *  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.
 */

package org.akvo.flow.domain;

/**
 * data structure representing a dependency between questions. A dependency
 * consists of two values: a question ID and an answer value. When a question
 * contains a dependency, it will not be shown unless the question referenced by
 * the dependency's questionID has an answer that matches the answerValue in the
 * A question can have 0 or 1 dependencies.
 * 
 * @author Christopher Fagiani
 */
public class Dependency {
    private String question;
    private String answer;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isMatch(String val) {
        if (answer == null || val == null) {
            return answer == val;
        }

        for (String v : val.split("\\|", -1)) {
            for (String a : answer.split("\\|", -1)) {
                if (v.equals(a)) {
                    return true;
                }
            }
        }

        return false;
    }
}
