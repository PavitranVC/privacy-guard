#pragma once

#include <vector>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <cstring>

namespace privacyguard {

struct LightGbmNode {
    int32_t feature;
    double threshold;
    int32_t left;   // if left >= 0: node index; if left < 0: leaf index = (-left - 1)
    int32_t right;  // if right >= 0: node index; if right < 0: leaf index = (-right - 1)
    uint8_t default_left;
};

struct IsotonicPoint {
    double x;
    double y;
};

class LightGbmEvaluator {
public:
    static constexpr uint32_t MAGIC = 0x4C47424D; // "LGBM"
    static constexpr int NUM_CLASSES = 4;

    bool loadModel(const uint8_t* data, size_t size) {
        if (!data || size < 24) {
            is_loaded_ = false;
            return false;
        }

        uint32_t magic;
        std::memcpy(&magic, data, sizeof(magic));
        if (magic != MAGIC) {
            // Genesis mode / uninitialized binary
            is_loaded_ = false;
            return false;
        }

        size_t offset = 4;
        uint32_t version;
        std::memcpy(&version, data + offset, sizeof(version)); offset += 4;
        std::memcpy(&num_trees_, data + offset, sizeof(num_trees_)); offset += 4;
        uint32_t num_nodes;
        std::memcpy(&num_nodes, data + offset, sizeof(num_nodes)); offset += 4;
        uint32_t num_leaves;
        std::memcpy(&num_leaves, data + offset, sizeof(num_leaves)); offset += 4;

        if (offset + num_trees_ * sizeof(int32_t) > size) return false;
        tree_roots_.resize(num_trees_);
        std::memcpy(tree_roots_.data(), data + offset, num_trees_ * sizeof(int32_t));
        offset += num_trees_ * sizeof(int32_t);

        if (offset + num_nodes * sizeof(LightGbmNode) > size) return false;
        nodes_.resize(num_nodes);
        std::memcpy(nodes_.data(), data + offset, num_nodes * sizeof(LightGbmNode));
        offset += num_nodes * sizeof(LightGbmNode);

        if (offset + num_leaves * sizeof(double) > size) return false;
        leaves_.resize(num_leaves);
        std::memcpy(leaves_.data(), data + offset, num_leaves * sizeof(double));
        offset += num_leaves * sizeof(double);

        // Isotonic calibration points per class
        for (int c = 0; c < NUM_CLASSES; ++c) {
            if (offset + sizeof(uint32_t) <= size) {
                uint32_t num_pts;
                std::memcpy(&num_pts, data + offset, sizeof(num_pts));
                offset += sizeof(uint32_t);
                if (offset + num_pts * sizeof(IsotonicPoint) <= size) {
                    isotonic_curves_[c].resize(num_pts);
                    std::memcpy(isotonic_curves_[c].data(), data + offset, num_pts * sizeof(IsotonicPoint));
                    offset += num_pts * sizeof(IsotonicPoint);
                }
            }
        }

        is_loaded_ = true;
        return true;
    }

    double evaluate(const std::vector<float>& features) const {
        if (!is_loaded_ || tree_roots_.empty()) {
            return fallbackHeuristicScore(features);
        }

        double raw_scores[NUM_CLASSES] = {0.0, 0.0, 0.0, 0.0};

        for (size_t t = 0; t < tree_roots_.size(); ++t) {
            int target_class = static_cast<int>(t % NUM_CLASSES);
            int node = tree_roots_[t];

            while (node >= 0 && static_cast<size_t>(node) < nodes_.size()) {
                const auto& nd = nodes_[node];
                double val = 0.0;
                bool is_nan = true;
                if (nd.feature >= 0 && static_cast<size_t>(nd.feature) < features.size()) {
                    val = static_cast<double>(features[nd.feature]);
                    is_nan = std::isnan(val);
                }

                int next_idx;
                if (is_nan) {
                    next_idx = nd.default_left ? nd.left : nd.right;
                } else if (val <= nd.threshold) {
                    next_idx = nd.left;
                } else {
                    next_idx = nd.right;
                }

                if (next_idx < 0) {
                    size_t leaf_idx = static_cast<size_t>(-next_idx - 1);
                    if (leaf_idx < leaves_.size()) {
                        raw_scores[target_class] += leaves_[leaf_idx];
                    }
                    break;
                }
                node = next_idx;
            }
        }

        // Multiclass Softmax
        double max_s = raw_scores[0];
        for (int c = 1; c < NUM_CLASSES; ++c) {
            if (raw_scores[c] > max_s) max_s = raw_scores[c];
        }

        double exp_sum = 0.0;
        double probs[NUM_CLASSES];
        for (int c = 0; c < NUM_CLASSES; ++c) {
            probs[c] = std::exp(raw_scores[c] - max_s);
            exp_sum += probs[c];
        }
        for (int c = 0; c < NUM_CLASSES; ++c) {
            probs[c] = (exp_sum > 0.0) ? (probs[c] / exp_sum) : 0.25;
        }

        // Piecewise-linear isotonic calibration
        double calibrated[NUM_CLASSES];
        double calib_sum = 0.0;
        for (int c = 0; c < NUM_CLASSES; ++c) {
            calibrated[c] = calibrateProbability(c, probs[c]);
            calib_sum += calibrated[c];
        }

        // Renormalization
        if (calib_sum > 0.0) {
            for (int c = 0; c < NUM_CLASSES; ++c) {
                calibrated[c] /= calib_sum;
            }
        }

        // Weighted risk score (Class 0: Benign, Class 1: Low, Class 2: Med, Class 3: High)
        double risk = calibrated[1] * 0.33 + calibrated[2] * 0.66 + calibrated[3] * 1.0;
        return std::max(0.0, std::min(1.0, risk));
    }

private:
    double calibrateProbability(int class_idx, double p) const {
        const auto& curve = isotonic_curves_[class_idx];
        if (curve.size() < 2) return p;

        if (p <= curve.front().x) return curve.front().y;
        if (p >= curve.back().x) return curve.back().y;

        for (size_t i = 0; i + 1 < curve.size(); ++i) {
            if (p >= curve[i].x && p <= curve[i + 1].x) {
                double dx = curve[i + 1].x - curve[i].x;
                if (dx <= 1e-9) return curve[i].y;
                double t = (p - curve[i].x) / dx;
                return curve[i].y + t * (curve[i + 1].y - curve[i].y);
            }
        }
        return p;
    }

    double fallbackHeuristicScore(const std::vector<float>& features) const {
        double score = 0.05;
        if (features.size() > 6) {
            score += static_cast<double>(features[6]) * 0.12;
        }
        if (features.size() > 5 && features[5] > 0.5f) {
            score += 0.25;
        }
        if (features.size() > 4 && features[4] > 6.8f) {
            score += 0.15;
        }
        if (features.size() > 9 && features[9] > 0.5f) {
            score += 0.20;
        }
        return std::max(0.0, std::min(1.0, score));
    }

    bool is_loaded_ = false;
    uint32_t num_trees_ = 0;
    std::vector<int32_t> tree_roots_;
    std::vector<LightGbmNode> nodes_;
    std::vector<double> leaves_;
    std::vector<IsotonicPoint> isotonic_curves_[NUM_CLASSES];
};

} // namespace privacyguard
