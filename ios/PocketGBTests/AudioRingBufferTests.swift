import Testing
@testable import PocketGB

struct AudioRingBufferTests {
    @Test func writeAndReadPreserveOrderAcrossWraparound() {
        let ring = AudioRingBuffer(capacityFrames: 4)
        let first: [Int16] = [1, 11, 2, 12, 3, 13]
        #expect(first.withUnsafeBufferPointer { ring.write(from: $0.baseAddress!, frames: 3) } == 3)

        var left = [Float](repeating: 0, count: 2)
        var right = [Float](repeating: 0, count: 2)
        read(ring, left: &left, right: &right)

        let second: [Int16] = [4, 14, 5, 15, 6, 16]
        #expect(second.withUnsafeBufferPointer { ring.write(from: $0.baseAddress!, frames: 3) } == 3)
        left = [Float](repeating: 0, count: 4)
        right = [Float](repeating: 0, count: 4)
        read(ring, left: &left, right: &right)

        #expect(left == [3, 4, 5, 6].map { Float($0) / 32768 })
        #expect(right == [13, 14, 15, 16].map { Float($0) / 32768 })
        #expect(ring.availableFrames == 0)
    }

    @Test func fullBufferAcceptsOnlyFreeFrames() {
        let ring = AudioRingBuffer(capacityFrames: 4)
        let samples: [Int16] = [1, 10, 2, 20, 3, 30, 4, 40, 5, 50]

        #expect(samples.withUnsafeBufferPointer { ring.write(from: $0.baseAddress!, frames: 5) } == 4)
        #expect(ring.availableFrames == 4)
    }

    @Test func underrunRepeatsLastStereoFrameAndCountsOnce() {
        let ring = AudioRingBuffer(capacityFrames: 4)
        let samples: [Int16] = [1_000, -2_000, 3_000, -4_000]
        #expect(samples.withUnsafeBufferPointer { ring.write(from: $0.baseAddress!, frames: 2) } == 2)

        var left = [Float](repeating: 0, count: 4)
        var right = [Float](repeating: 0, count: 4)
        read(ring, left: &left, right: &right)

        #expect(left == [1_000, 3_000, 3_000, 3_000].map { Float($0) / 32768 })
        #expect(right == [-2_000, -4_000, -4_000, -4_000].map { Float($0) / 32768 })
        #expect(ring.underruns == 1)
    }

    @Test func convertsInt16ExtremesToFloat() {
        let ring = AudioRingBuffer(capacityFrames: 2)
        let samples: [Int16] = [.min, .max]
        #expect(samples.withUnsafeBufferPointer { ring.write(from: $0.baseAddress!, frames: 1) } == 1)

        var left = [Float](repeating: 0, count: 1)
        var right = [Float](repeating: 0, count: 1)
        read(ring, left: &left, right: &right)

        #expect(left[0] == -1)
        #expect(right[0] == Float(Int16.max) / 32768)
    }

    private func read(_ ring: AudioRingBuffer, left: inout [Float], right: inout [Float]) {
        let frames = left.count
        left.withUnsafeMutableBufferPointer { leftBuffer in
            right.withUnsafeMutableBufferPointer { rightBuffer in
                ring.read(intoLeft: leftBuffer.baseAddress!, right: rightBuffer.baseAddress!, frames: frames)
            }
        }
    }
}
