// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "FosaMobile", platforms: [.iOS(.v16)], products: [.library(name: "FosaMobile", targets: ["FosaMobile"])], targets: [.target(name: "FosaMobile")])
