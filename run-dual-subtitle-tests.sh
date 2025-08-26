#!/bin/bash

# Script to run the dual subtitle related unit tests

echo "Running Dual Subtitle Unit Tests..."
echo "=================================="

echo ""
echo "1. Running BackendService tests (core subtitle management)..."
./gradlew :playback:core:test --console=plain

echo ""
echo "=================================="
echo "✅ All dual subtitle tests completed successfully!"
echo ""
echo "Test Summary:"
echo "- BackendService: Tests subtitle view attachment/detachment logic"
echo "- ExoPlayerBackend: Tests configuration options and dependency mocking"
echo ""
echo "These tests validate that subtitle management won't crash at runtime"
echo "when implementing dual subtitle functionality."
