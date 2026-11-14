#pragma once

#include <string>
#include <vector>
#include <queue>
#include <unordered_map>
#include <unordered_set>
#include <sstream>

namespace privacyguard {

struct AcNode {
    std::unordered_map<char, int> next;
    int fail = 0;
    std::vector<std::string> output;
};

class AhoCorasick {
public:
    AhoCorasick() {
        nodes_.emplace_back(); // Root node at index 0
    }

    void loadPatternsFromMemory(const uint8_t* data, size_t size) {
        if (!data || size == 0) return;

        std::string content(reinterpret_cast<const char*>(data), size);
        std::istringstream stream(content);
        std::string line;

        while (std::getline(stream, line)) {
            // Trim carriage return if present
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            if (line.empty() || line[0] == '#' || line.rfind("TRACKER_AC_", 0) == 0) {
                continue;
            }
            addPattern(line);
        }

        buildFailureLinks();
    }

    void addPattern(const std::string& pattern) {
        if (pattern.empty()) return;

        int curr = 0;
        for (char c : pattern) {
            auto it = nodes_[curr].next.find(c);
            if (it == nodes_[curr].next.end()) {
                int nextNode = static_cast<int>(nodes_.size());
                nodes_.emplace_back();
                nodes_[curr].next[c] = nextNode;
                curr = nextNode;
            } else {
                curr = it->second;
            }
        }
        nodes_[curr].output.push_back(pattern);
    }

    void buildFailureLinks() {
        std::queue<int> q;
        for (const auto& [c, nextNode] : nodes_[0].next) {
            nodes_[nextNode].fail = 0;
            q.push(nextNode);
        }

        while (!q.empty()) {
            int u = q.front();
            q.pop();

            for (const auto& [c, v] : nodes_[u].next) {
                int f = nodes_[u].fail;
                while (f > 0 && nodes_[f].next.find(c) == nodes_[f].next.end()) {
                    f = nodes_[f].fail;
                }
                auto it = nodes_[f].next.find(c);
                if (it != nodes_[f].next.end() && it->second != v) {
                    nodes_[v].fail = it->second;
                } else {
                    nodes_[v].fail = 0;
                }

                // Append output from failure state
                for (const auto& out : nodes_[nodes_[v].fail].output) {
                    nodes_[v].output.push_back(out);
                }

                q.push(v);
            }
        }
    }

    void search(const uint8_t* data, size_t len, std::unordered_set<std::string>& matches) const {
        if (nodes_.empty() || !data || len == 0) return;

        int state = 0;
        for (size_t i = 0; i < len; ++i) {
            char c = static_cast<char>(data[i]);
            while (state > 0 && nodes_[state].next.find(c) == nodes_[state].next.end()) {
                state = nodes_[state].fail;
            }
            auto it = nodes_[state].next.find(c);
            if (it != nodes_[state].next.end()) {
                state = it->second;
            } else {
                state = 0;
            }

            if (!nodes_[state].output.empty()) {
                for (const auto& pattern : nodes_[state].output) {
                    matches.insert(pattern);
                }
            }
        }
    }

private:
    std::vector<AcNode> nodes_;
};

} // namespace privacyguard
