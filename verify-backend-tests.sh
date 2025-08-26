#!/bin/bash

echo "Verifying BackendService tests..."
echo "=================================="

# Clean and run tests with info output to see what's happening
echo "Running tests with verbose output:"
./gradlew :playback:core:clean :playback:core:test --info 2>&1 | grep -E "(BackendServiceTests|executing|PASSED|FAILED)"

echo ""
echo "=================================="
echo "Test verification complete!"