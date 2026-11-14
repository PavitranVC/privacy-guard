#pragma once

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <zlib.h>

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <vector>
#include <functional>

namespace privacyguard {

constexpr uint32_t ZIP_CD_SIGNATURE = 0x02014b50;
constexpr uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr uint32_t ZIP_LOCAL_HDR_SIGNATURE = 0x04034b50;
constexpr size_t MAX_UNCOMPRESSED_ENTRY_SIZE = 64 * 1024 * 1024; // 64 MB protection cap against ZIP bombs

struct ZipEntry {
    std::string name;
    uint16_t method;
    uint32_t compressedSize;
    uint32_t uncompressedSize;
    uint32_t localHeaderOffset;
};

class ApkArchive {
public:
    explicit ApkArchive(const std::string& path) : path_(path), fd_(-1), mapped_(MAP_FAILED), fileSize_(0) {}

    ~ApkArchive() {
        closeArchive();
    }

    bool openArchive() {
        fd_ = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd_ < 0) return false;

        struct stat st{};
        if (fstat(fd_, &st) != 0 || st.st_size <= 22) {
            close(fd_);
            fd_ = -1;
            return false;
        }

        fileSize_ = static_cast<size_t>(st.st_size);
        long page_size = sysconf(_SC_PAGESIZE);
        if (page_size <= 0) page_size = 16384;

        mapped_ = mmap(nullptr, fileSize_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (mapped_ == MAP_FAILED) {
            close(fd_);
            fd_ = -1;
            return false;
        }

        madvise(mapped_, fileSize_, MADV_SEQUENTIAL);
        return parseCentralDirectory();
    }

    void closeArchive() {
        if (mapped_ != MAP_FAILED) {
            munmap(mapped_, fileSize_);
            mapped_ = MAP_FAILED;
        }
        if (fd_ >= 0) {
            close(fd_);
            fd_ = -1;
        }
        entries_.clear();
        fileSize_ = 0;
    }

    const std::vector<ZipEntry>& getEntries() const {
        return entries_;
    }

    // Read or decompress entry payload into buffer. Enforces 64MB cap and overflow-safe arithmetic.
    bool extractEntry(const ZipEntry& entry, std::vector<uint8_t>& out) const {
        if (mapped_ == MAP_FAILED) return false;
        if (entry.uncompressedSize > MAX_UNCOMPRESSED_ENTRY_SIZE) return false;

        // Subtraction-based bounds check for local header
        if (entry.localHeaderOffset > fileSize_ || 30 > fileSize_ - entry.localHeaderOffset) {
            return false;
        }

        const uint8_t* raw = static_cast<const uint8_t*>(mapped_);
        const uint8_t* localHdr = raw + entry.localHeaderOffset;

        uint32_t sig;
        std::memcpy(&sig, localHdr, sizeof(sig));
        if (sig != ZIP_LOCAL_HDR_SIGNATURE) return false;

        uint16_t nameLen;
        uint16_t extraLen;
        std::memcpy(&nameLen, localHdr + 26, sizeof(nameLen));
        std::memcpy(&extraLen, localHdr + 28, sizeof(extraLen));

        size_t headerTotalLen = 30 + static_cast<size_t>(nameLen) + static_cast<size_t>(extraLen);
        if (headerTotalLen > fileSize_ - entry.localHeaderOffset) return false;

        size_t dataOffset = entry.localHeaderOffset + headerTotalLen;
        if (dataOffset > fileSize_ || entry.compressedSize > fileSize_ - dataOffset) {
            return false;
        }

        const uint8_t* compressedData = raw + dataOffset;

        if (entry.method == 0) {
            // Stored
            out.resize(entry.uncompressedSize);
            if (entry.uncompressedSize > 0) {
                std::memcpy(out.data(), compressedData, entry.uncompressedSize);
            }
            return true;
        } else if (entry.method == 8) {
            // Deflated - raw inflateInit2 with negative MAX_WBITS
            out.resize(entry.uncompressedSize);

            z_stream strm{};
            strm.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(compressedData));
            strm.avail_in = entry.compressedSize;
            strm.next_out = reinterpret_cast<Bytef*>(out.data());
            strm.avail_out = entry.uncompressedSize;

            if (inflateInit2(&strm, -MAX_WBITS) != Z_OK) {
                return false;
            }

            int ret = inflate(&strm, Z_FINISH);
            inflateEnd(&strm);

            return (ret == Z_STREAM_END || ret == Z_OK);
        }

        return false;
    }

private:
    bool parseCentralDirectory() {
        if (mapped_ == MAP_FAILED || fileSize_ < 22) return false;

        const uint8_t* raw = static_cast<const uint8_t*>(mapped_);

        // Locate End of Central Directory (EOCD)
        size_t maxSearch = (fileSize_ > 65557) ? 65557 : fileSize_;
        size_t minOffset = fileSize_ - maxSearch;
        size_t eocdOffset = 0;
        bool foundEocd = false;

        for (size_t i = fileSize_ - 22; i >= minOffset; --i) {
            uint32_t sig;
            std::memcpy(&sig, raw + i, sizeof(sig));
            if (sig == ZIP_EOCD_SIGNATURE) {
                eocdOffset = i;
                foundEocd = true;
                break;
            }
            if (i == 0) break;
        }

        if (!foundEocd) return false;

        // EOCD structure:
        // offset 10: total central directory records (uint16_t)
        // offset 12: size of central directory (uint32_t)
        // offset 16: offset of start of central directory (uint32_t)
        uint16_t totalEntries;
        uint32_t cdSize;
        uint32_t cdOffset;
        std::memcpy(&totalEntries, raw + eocdOffset + 10, sizeof(totalEntries));
        std::memcpy(&cdSize, raw + eocdOffset + 12, sizeof(cdSize));
        std::memcpy(&cdOffset, raw + eocdOffset + 16, sizeof(cdOffset));

        // Subtraction-based bounds validation
        if (cdOffset > fileSize_ || cdSize > fileSize_ - cdOffset) {
            return false;
        }

        size_t currOffset = cdOffset;
        for (uint16_t i = 0; i < totalEntries; ++i) {
            if (currOffset > fileSize_ || 46 > fileSize_ - currOffset) {
                break;
            }

            const uint8_t* cdHdr = raw + currOffset;
            uint32_t sig;
            std::memcpy(&sig, cdHdr, sizeof(sig));
            if (sig != ZIP_CD_SIGNATURE) {
                break;
            }

            uint16_t method;
            uint32_t compSize;
            uint32_t uncompSize;
            uint16_t nameLen;
            uint16_t extraLen;
            uint16_t commentLen;
            uint32_t localHdrOffset;

            std::memcpy(&method, cdHdr + 10, sizeof(method));
            std::memcpy(&compSize, cdHdr + 20, sizeof(compSize));
            std::memcpy(&uncompSize, cdHdr + 24, sizeof(uncompSize));
            std::memcpy(&nameLen, cdHdr + 28, sizeof(nameLen));
            std::memcpy(&extraLen, cdHdr + 30, sizeof(extraLen));
            std::memcpy(&commentLen, cdHdr + 32, sizeof(commentLen));
            std::memcpy(&localHdrOffset, cdHdr + 42, sizeof(localHdrOffset));

            size_t entryHeaderTotal = 46 + static_cast<size_t>(nameLen) + extraLen + commentLen;
            if (entryHeaderTotal > fileSize_ - currOffset) {
                break;
            }

            std::string entryName(reinterpret_cast<const char*>(cdHdr + 46), nameLen);

            ZipEntry entry;
            entry.name = std::move(entryName);
            entry.method = method;
            entry.compressedSize = compSize;
            entry.uncompressedSize = uncompSize;
            entry.localHeaderOffset = localHdrOffset;

            entries_.push_back(std::move(entry));
            currOffset += entryHeaderTotal;
        }

        return !entries_.empty();
    }

    std::string path_;
    int fd_;
    void* mapped_;
    size_t fileSize_;
    std::vector<ZipEntry> entries_;
};

} // namespace privacyguard
