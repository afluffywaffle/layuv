// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LeamhDocx",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "LeamhDocx", targets: ["LeamhDocx"]),
    ],
    dependencies: [
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", from: "0.9.19"),
    ],
    targets: [
        .target(
            name: "LeamhDocx",
            dependencies: ["ZIPFoundation"]
        ),
        .testTarget(
            name: "LeamhDocxTests",
            dependencies: ["LeamhDocx"],
            resources: [
                .copy("Resources/golden"),
            ]
        ),
    ]
)
