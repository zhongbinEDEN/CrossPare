// Copyright 2015 Georg-August-Universität Göttingen, Germany
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package de.ugoe.cs.cpdp.wekaclassifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.ugoe.cs.util.console.Console;
import weka.classifiers.AbstractClassifier;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Discretize;

/**
 * <p>
 * WHICH classifier after Menzies et al.
 * </p>
 * 
 * @author Steffen Herbold
 */
public class WHICH extends AbstractClassifier {

    /**
     * default id.
     */
    private static final long serialVersionUID = 1L;

    /**
     * number of bins used for discretization of data
     */
    private int numBins = 7;

    /**
     * number of new rules generate within each rule generation iteration
     */
    private final int numNewRules = 5;

    /**
     * number of rule generation iterations
     */
    private final int newRuleIterations = 20;

    /**
     * maximal number of tries to improve the best score
     */
    private final int maxIter = 100;

    /**
     * best rule determined by the training, i.e., the classifier
     */
    public WhichRule bestRule = null;

    /*
     * (non-Javadoc)
     * 
     * @see weka.classifiers.Classifier#buildClassifier(weka.core.Instances)
     */
    @SuppressWarnings("boxing")
    @Override
    public void buildClassifier(Instances traindata) throws Exception {
        WhichStack whichStack = new WhichStack();
        Instances discretizedData = null;

        Set<Integer> ignoredAttributes = new HashSet<>();
        while (discretizedData == null && ignoredAttributes.size() < traindata.numAttributes()) {
            // list of attributes for Discretize filter
            StringBuilder attributeListBuilder = new StringBuilder();
            for (int j = 0; j < traindata.numAttributes(); j++) {
                if (j != traindata.classIndex() && !ignoredAttributes.contains(j)) {
                    attributeListBuilder.append((j + 1) + ",");
                }
            }
            String attributeList =
                attributeListBuilder.toString().substring(0, attributeListBuilder.length() - 1);

            Discretize discretize = new Discretize();
            discretize.setBins(this.numBins);
            discretize.setIgnoreClass(true);
            discretize.setAttributeIndices(attributeList);
            discretize.setInputFormat(traindata);
            try {
                discretizedData = Filter.useFilter(traindata, discretize);
            }
            catch (IllegalArgumentException e) {
                if (e.getMessage() != null &&
                    e.getMessage().contains("cannot have duplicate labels"))
                {
                    Pattern pattern = Pattern.compile("\\((.*?)\\)");
                    Matcher matcher = pattern.matcher(e.getMessage());
                    if (matcher.find()) {
                        Console
                            .traceln(Level.WARNING,
                                     "ignoring attribute for WHICH training because it cannot be discretized: " +
                                         matcher.group(1));
                        ignoredAttributes.add(traindata.attribute(matcher.group(1)).index());
                    }
                }
                else {
                    throw e;
                }
            }
        }
        if (discretizedData == null) {
            throw new RuntimeException("could not discretize data for WHICH training");
        }
        // init WHICH stack
        for (int j = 0; j < discretizedData.numAttributes(); j++) {
            Attribute attr = discretizedData.attribute(j);
            for (int k = 0; k < attr.numValues(); k++) {
                // create rules for single variables
                WhichRule rule = new WhichRule(Arrays.asList(new Integer[]
                    { j }), Arrays.asList(new Double[]
                    { (double) k }), Arrays.asList(new String[]
                    { attr.value(k) }));
                rule.scoreRule(discretizedData);
                whichStack.push(rule);
            }
        }
        double curBestScore = whichStack.bestScore;
        int iter = 0;
        do {
            // generate new rules
            for (int i = 0; i < this.newRuleIterations; i++) {
                whichStack.generateRules(this.numNewRules, discretizedData);
            }
            if (curBestScore >= whichStack.bestScore) {
                // no improvement, terminate
                break;
            }
            curBestScore = whichStack.bestScore;
            iter++;
        }
        while (iter < this.maxIter);

        this.bestRule = whichStack.bestRule();
    }

    /*
     * (non-Javadoc)
     * 
     * @see weka.classifiers.AbstractClassifier#classifyInstance(weka.core.Instance)
     */
    @Override
    public double classifyInstance(Instance instance) {
        if (this.bestRule == null) {
            throw new RuntimeException("you have to build the classifier first!");
        }
        return this.bestRule.applyRule(instance, false) ? 0.0 : 1.0;
    }

    /**
     * <p>
     * Internal helper class to handle WHICH rules. The compareTo method is NOT consistent with the
     * equals method!
     * </p>
     * 
     * @author Steffen Herbold
     */
    private class WhichRule implements Comparable<WhichRule> {
        /**
         * indizes of the attributes in the data to which the rule is applied
         */
        final List<Integer> attributeIndizes;

        /**
         * index of the range for internal optimization during training
         */
        final List<Double> rangeIndizes;

        /**
         * String of the range as created by Discretize.
         */
        final List<String> ranges;

        /**
         * support of the rule
         */
        double support;

        /**
         * percentage of the defective matches where the rule applies
         */
        double e1;

        /**
         * percentage of the non-defective matches where the rule does not apply
         */
        double e2;

        /**
         * score of the rule
         */
        double score;

        /**
         * <p>
         * Creates a new WhichRule.
         * </p>
         *
         * @param attributeIndizes
         *            attribute indizes
         * @param rangeIndizes
         *            range indizes
         * @param ranges
         *            range strings
         */
        @SuppressWarnings("hiding")
        public WhichRule(List<Integer> attributeIndizes,
                         List<Double> rangeIndizes,
                         List<String> ranges)
        {
            this.attributeIndizes = attributeIndizes;
            this.rangeIndizes = rangeIndizes;
            this.ranges = ranges;
        }

        /**
         * <p>
         * Combines two rules into a new rule
         * </p>
         *
         * @param rule1
         *            first rule in combination
         * @param rule2
         *            second rule in combination
         */
        public WhichRule(WhichRule rule1, WhichRule rule2) {
            this.attributeIndizes = new ArrayList<>(rule1.attributeIndizes);
            this.rangeIndizes = new ArrayList<>(rule1.rangeIndizes);
            this.ranges = new ArrayList<>(rule1.ranges);
            for (int k = 0; k < rule2.attributeIndizes.size(); k++) {
                if (!this.attributeIndizes.contains(rule2.attributeIndizes.get(k))) {
                    this.attributeIndizes.add(rule2.attributeIndizes.get(k));
                    this.rangeIndizes.add(rule2.rangeIndizes.get(k));
                    this.ranges.add(rule2.ranges.get(k));
                }
            }
        }

        /**
         * <p>
         * Determines the score of a rule.
         * </p>
         *
         * @param traindata
         *            data used for scoring
         */
        public void scoreRule(Instances traindata) {
            int numMatches = 0;
            int numMatchDefective = 0;
            int numMatchNondefective = 0;
            @SuppressWarnings("unused")
            int numNoMatchDefective = 0;
            @SuppressWarnings("unused")
            int numNoMatchNondefective = 0;
            for (int i = 0; i < traindata.size(); i++) {
                // check if rule applies
                if (applyRule(traindata.get(i), true)) {
                    // to something
                    numMatches++;
                    if (traindata.get(i).classValue() == 1.0) {
                        numMatchDefective++;
                    }
                    else {
                        numMatchNondefective++;
                    }
                }
                else {
                    if (traindata.get(i).classValue() == 1.0) {
                        numNoMatchDefective++;
                    }
                    else {
                        numNoMatchNondefective++;
                    }
                }
            }
            this.support = numMatches / ((double) traindata.size());
            if (numMatches > 0) {
                this.e1 = numMatchNondefective / ((double) numMatches);
                this.e2 = numMatchDefective / ((double) numMatches);
                if (this.e2 > 0) {
                    this.score = this.e1 / this.e2 * this.support;
                }
                else {
                    this.score = 0;
                }
            }
            else {
                this.e1 = 0;
                this.e2 = 0;
                this.score = 0;
            }
            if (this.score == 0) {
                this.score = 0.000000001; // to disallow 0 total score
            }
        }

        /**
         * <p>
         * Checks if a rule applies to an instance.
         * </p>
         *
         * @param instance
         *            the instance
         * @param isTraining
         *            if true, the data is discretized training data and rangeIndizes are used;
         *            otherwise the data is numeric and the range string is used.
         * @return true if the rule applies
         */
        @SuppressWarnings("boxing")
        public boolean applyRule(Instance instance, boolean isTraining) {
            boolean result = true;
            for (int k = 0; k < this.attributeIndizes.size(); k++) {
                int attrIndex = this.attributeIndizes.get(k);
                if (isTraining) {
                    double rangeIndex = this.rangeIndizes.get(k);
                    double instanceValue = instance.value(attrIndex);
                    result &= (instanceValue == rangeIndex);
                }
                else {
                    String range = this.ranges.get(k);
                    if ("'All'".equals(range)) {
                        result = true;
                    }
                    else {
                        double instanceValue = instance.value(attrIndex);
                        double lowerBound;
                        double upperBound;
                        // set to -1.0 for negative second value
                        double secondMultiplier = 1.0; 
                        String[] splitResult = range.split("--");
                        if (splitResult.length > 1) {
                            // second value is negative
                            secondMultiplier = -1.0;
                            range.replace("--", "-");
                        }
                        splitResult = range.split("-");
                        if (splitResult.length > 2) {
                            // first value is negative
                            if ("inf".equals(splitResult[1])) {
                                lowerBound = Double.NEGATIVE_INFINITY;
                            }
                            else {
                                lowerBound = -Double.parseDouble(splitResult[1]);
                            }
                            if (splitResult[2].startsWith("inf")) {
                                upperBound = secondMultiplier*Double.POSITIVE_INFINITY;
                            }
                            else {
                                upperBound = secondMultiplier*Double.parseDouble(splitResult[2]
                                    .substring(0, splitResult[2].length() - 2));
                            }
                        }
                        else {
                            // first value is positive
                            if( secondMultiplier<0.0 ) {
                                throw new RuntimeException("Invalid Range, first value positive, second value negative: " + range);
                            }
                            if (splitResult[0].substring(2, splitResult[0].length())
                                .equals("ll'"))
                            {
                                System.out.println("foo");
                            }
                            lowerBound = Double
                                .parseDouble(splitResult[0].substring(2, splitResult[0].length()));
                            if (splitResult[1].startsWith("inf")) {
                                upperBound = Double.POSITIVE_INFINITY;
                            }
                            else {
                                upperBound = Double.parseDouble(splitResult[1]
                                    .substring(0, splitResult[1].length() - 2));
                            }
                        }
                        boolean lowerBoundMatch =
                            (range.charAt(1) == '(' && instanceValue > lowerBound) ||
                                (range.charAt(1) == '[' && instanceValue >= lowerBound);
                        boolean upperBoundMatch = (range.charAt(range.length() - 2) == ')' &&
                            instanceValue < upperBound) ||
                            (range.charAt(range.length() - 2) == ']' &&
                                instanceValue <= upperBound);
                        result = lowerBoundMatch && upperBoundMatch;
                    }
                }
            }
            return result;
        }

        /**
         * <p>
         * returns the score of the rule
         * </p>
         *
         * @return the score
         */
        public double getScore() {
            return this.score;
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Comparable#compareTo(java.lang.Object)
         */
        @Override
        public int compareTo(WhichRule other) {
            // !!this compareTo is NOT consistent with equals!!
            if (other == null) {
                return -1;
            }
            if (other.score < this.score) {
                return -1;
            }
            else if (other.score > this.score) {
                return 1;
            }
            else {
                return 0;
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (!(other instanceof WhichRule)) {
                return false;
            }
            WhichRule otherRule = (WhichRule) other;
            return this.attributeIndizes.equals(otherRule.attributeIndizes) &&
                this.rangeIndizes.equals(otherRule.rangeIndizes) &&
                this.ranges.equals(otherRule.ranges);
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return 117 + this.attributeIndizes.hashCode() + this.rangeIndizes.hashCode() +
                this.ranges.hashCode();
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            return "indizes: " + this.attributeIndizes + "\tranges: " + this.ranges + "\t score: " +
                this.score;
        }
    }

    /**
     * <p>
     * Internal helper class that handles the WHICH stack during training. Please not that this is
     * not really a stack, we just stick to the name given in the publication.
     * </p>
     * 
     * @author Steffen Herbold
     */
    private class WhichStack {

        /**
         * rules on the WhichStack
         */
        List<WhichRule> rules;

        /**
         * Currently sum of rule scores.
         */
        double scoreSum;

        /**
         * Best rule score.
         */
        double bestScore;

        /**
         * checks if a rule was added after the last sorting
         */
        boolean pushAfterSort;

        /**
         * Internally used random number generator for creating new rules.
         */
        Random rand = new Random();

        /**
         * <p>
         * Creates a new WhichStack.
         * </p>
         *
         */
        public WhichStack() {
            this.rules = new LinkedList<>();
            this.scoreSum = 0.0;
            this.bestScore = 0.0;
            this.pushAfterSort = false;
        }

        /**
         * <p>
         * Adds a rule to the WhichStack
         * </p>
         *
         * @param rule
         *            that is added.
         */
        public void push(WhichRule rule) {
            this.rules.add(rule);
            this.scoreSum += rule.getScore();
            if (rule.getScore() > this.bestScore) {
                this.bestScore = rule.getScore();
            }
            this.pushAfterSort = true;
        }

        /**
         * <p>
         * Generates a new rule as a random combination of two other rules. The two rules are drawn
         * according to their scoring.
         * </p>
         *
         * @param numRules
         *            numberof rules to be generated
         * @param traindata
         *            training data used for scoring
         */
        public void generateRules(int numRules, Instances traindata) {
            List<WhichRule> newRules = new LinkedList<>();

            for (int i = 0; i < numRules; i++) {
                WhichRule newRule;
                do {
                    WhichRule rule1 = drawRule();
                    WhichRule rule2;
                    do {
                        rule2 = drawRule();
                    }
                    while (rule2.equals(rule1));
                    newRule = new WhichRule(rule1, rule2);
                }
                while (newRules.contains(newRule));
                newRules.add(newRule);
            }
            for (WhichRule newRule : newRules) {
                newRule.scoreRule(traindata);
                push(newRule);
            }
        }

        /**
         * <p>
         * Randomly draws a rule weighted by the score.
         * </p>
         *
         * @return drawn rule
         */
        public WhichRule drawRule() {
            double randVal = this.rand.nextDouble() * this.scoreSum;
            double curSum = 0.0;
            for (WhichRule rule : this.rules) {
                curSum += rule.getScore();
                if (curSum >= randVal) {
                    return rule;
                }
            }
            Console.traceln(Level.SEVERE, "could not draw rule; bug in WhichStack.drawRule()");
            return null;
        }

        /**
         * <p>
         * Returns the best rule.
         * </p>
         *
         * @return best rule
         */
        public WhichRule bestRule() {
            if (this.rules.isEmpty()) {
                return null;
            }
            if (this.pushAfterSort) {
                Collections.sort(this.rules);
            }
            return this.rules.get(0);
        }
    }
}
