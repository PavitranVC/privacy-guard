#pragma once

#include <string>
#include <vector>
#include <queue>
#include <unordered_set>
#include <sstream>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace privacyguard {

struct FlatAcNode {
    uint32_t next[256] = {0};
    uint32_t fail = 0;
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
            while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) {
                line.pop_back();
            }
            if (line.empty() || line[0] == '#' || line.rfind("TRACKER_AC_", 0) == 0) {
                continue;
            }

            // Dotted pattern
            addPattern(line, line);

            // Slash pattern: com.facebook.appevents -> com/facebook/appevents/
            std::string slashPattern = line;
            std::replace(slashPattern.begin(), slashPattern.end(), '.', '/');
            if (slashPattern.back() != '/') {
                slashPattern += '/';
            }
            addPattern(slashPattern, line);
        }

        buildFailureLinks();
    }

    void addPattern(const std::string& pattern, const std::string& trackerId) {
        if (pattern.empty() || nodes_.size() >= 65536) return;

        uint32_t curr = 0;
        for (unsigned char c : pattern) {
            if (nodes_[curr].next[c] == 0) {
                if (nodes_.size() >= 65536) return;
                uint32_t nextNode = static_cast<uint32_t>(nodes_.size());
                nodes_.emplace_back();
                nodes_[curr].next[c] = nextNode;
                curr = nextNode;
            } else {
                curr = nodes_[curr].next[c];
            }
        }
        nodes_[curr].output.push_back(trackerId);
    }

    void buildFailureLinks() {
        std::queue<uint32_t> q;
        for (int c = 0; c < 256; ++c) {
            uint32_t nextNode = nodes_[0].next[c];
            if (nextNode != 0) {
                nodes_[nextNode].fail = 0;
                q.push(nextNode);
            }
        }

        while (!q.empty()) {
            uint32_t u = q.front();
            q.pop();

            for (int c = 0; c < 256; ++c) {
                uint32_t v = nodes_[u].next[c];
                if (v != 0) {
                    uint32_t f = nodes_[u].fail;
                    while (f > 0 && nodes_[f].next[c] == 0) {
                        f = nodes_[f].fail;
                    }
                    nodes_[v].fail = nodes_[f].next[c];

                    // Merge output from failure link
                    for (const auto& out : nodes_[nodes_[v].fail].output) {
                        nodes_[v].output.push_back(out);
                    }
                    q.push(v);
                }
            }
        }
    }

    void search(const uint8_t* data, size_t len, std::unordered_set<std::string>& matches) const {
        if (nodes_.empty() || !data || len == 0) return;

        uint32_t state = 0;
        for (size_t i = 0; i < len; ++i) {
            unsigned char c = data[i];
            while (state > 0 && nodes_[state].next[c] == 0) {
                state = nodes_[state].fail;
            }
            state = nodes_[state].next[c];

            if (!nodes_[state].output.empty()) {
                for (const auto& pattern : nodes_[state].output) {
                    matches.insert(pattern);
                }
            }
        }
    }

private:
    std::vector<FlatAcNode> nodes_;
};

} // namespace privacyguard
