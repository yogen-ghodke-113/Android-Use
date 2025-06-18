# Contributing to Android-Use

Thank you for considering contributing to Android-Use! This document provides guidelines and information for contributors.

## 🚀 Getting Started

### Prerequisites

- Android Studio Arctic Fox or later
- Python 3.8+
- Git
- Basic knowledge of Kotlin/Android development and Python

### Development Setup

1. **Fork and clone the repository**:

   ```bash
   git clone https://github.com/your-username/android-use.git
   cd android-use
   ```

2. **Set up the Android development environment**:

   - Open the project in Android Studio
   - Install required SDK components
   - Sync Gradle files

3. **Set up the Python development environment**:
   ```bash
   cd adk-droid
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

## 📝 Code Style Guidelines

### Kotlin/Android

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions small and focused
- Use dependency injection where appropriate

### Python

- Follow [PEP 8](https://pep8.org/) style guidelines
- Use type hints for function parameters and return values
- Write docstrings for all functions and classes
- Use meaningful variable names
- Keep functions under 50 lines when possible

## 🐛 Reporting Issues

### Before Submitting an Issue

1. **Search existing issues** to avoid duplicates
2. **Test with the latest version** to ensure the issue still exists
3. **Gather relevant information** (device model, Android version, logs, etc.)

### Issue Templates

When creating an issue, please include:

- **Clear description** of the problem
- **Steps to reproduce** the issue
- **Expected vs actual behavior**
- **Environment details** (Android version, device model, server version)
- **Relevant logs** (with sensitive information removed)

## 🔧 Making Changes

### Branch Naming Convention

- `feature/feature-name` - New features
- `fix/issue-description` - Bug fixes
- `docs/update-description` - Documentation updates
- `refactor/component-name` - Code refactoring

### Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
type(scope): description

body (optional)

footer (optional)
```

Examples:

- `feat(android): add new gesture recognition`
- `fix(server): resolve WebSocket connection timeout`
- `docs(readme): update installation instructions`

### Pull Request Process

1. **Create a feature branch** from `main`
2. **Make your changes** following the code style guidelines
3. **Write or update tests** for your changes
4. **Update documentation** if necessary
5. **Test thoroughly** on both Android and server components
6. **Submit a pull request** with a clear description

### Pull Request Template

When submitting a PR, include:

- **Description** of changes made
- **Related issue(s)** (if any)
- **Type of change** (bug fix, feature, docs, etc.)
- **Testing performed**
- **Screenshots** (for UI changes)

## 🧪 Testing

### Android Testing

Run Android unit tests:

```bash
./gradlew test
```

Run Android instrumentation tests:

```bash
./gradlew connectedAndroidTest
```

### Python Testing

Run Python tests:

```bash
cd adk-droid
python -m pytest
```

Run with coverage:

```bash
python -m pytest --cov=android_use_agent
```

### Manual Testing

Before submitting changes:

1. **Test basic functionality** (connection, simple commands)
2. **Test edge cases** (network failures, permission denials)
3. **Test on different devices** if possible
4. **Verify accessibility compliance**

## 📚 Documentation

### Code Documentation

- **Android**: Use KDoc for public APIs
- **Python**: Use docstrings following Google style
- **Comments**: Explain why, not what
- **README updates**: Keep installation and usage instructions current

### API Documentation

When adding new features:

1. **Update relevant README sections**
2. **Add examples** of usage
3. **Document any new configuration options**
4. **Update troubleshooting guides** if needed

## 🏗️ Architecture Guidelines

### Android Client

- **Single Responsibility**: Each class should have one clear purpose
- **Dependency Injection**: Use proper DI patterns
- **Error Handling**: Always handle exceptions gracefully
- **Accessibility**: Ensure all UI is accessible
- **Permissions**: Request only necessary permissions

### Python Server

- **Modular Design**: Keep agents and tools separate
- **Async/Await**: Use async patterns for I/O operations
- **Error Handling**: Log errors appropriately
- **Type Safety**: Use type hints consistently
- **Configuration**: Use environment variables for config

### Communication Protocol

- **Backwards Compatibility**: Don't break existing message formats
- **Correlation IDs**: Always include for request/response matching
- **Error Responses**: Provide meaningful error messages
- **Timeouts**: Handle connection timeouts gracefully

## 🔐 Security Considerations

### When Contributing

- **Never commit** API keys, passwords, or credentials
- **Review dependencies** for security vulnerabilities
- **Validate inputs** on both client and server
- **Use secure communication** protocols
- **Follow privacy guidelines** for user data

### Sensitive Data

- Use environment variables for configuration
- Sanitize logs to remove personal information
- Don't store user data unnecessarily
- Follow GDPR and privacy best practices

## 📞 Getting Help

### Communication Channels

- **GitHub Issues**: For bug reports and feature requests
- **GitHub Discussions**: For questions and general discussion
- **Code Reviews**: Participate in PR reviews

### Mentorship

New contributors are welcome! If you need help:

1. **Check existing documentation** first
2. **Search closed issues** for similar problems
3. **Ask questions** in GitHub Discussions
4. **Tag maintainers** in issues when needed

## 🎯 Contribution Ideas

### Good First Issues

- **Documentation improvements**
- **Test coverage improvements**
- **UI/UX enhancements**
- **Bug fixes for known issues**
- **Code cleanup and refactoring**

### Advanced Contributions

- **New AI agent implementations**
- **Performance optimizations**
- **New action types**
- **Cross-platform support**
- **Integration with other services**

## 📋 Release Process

### Versioning

We use [Semantic Versioning](https://semver.org/):

- **MAJOR**: Breaking changes
- **MINOR**: New features (backwards compatible)
- **PATCH**: Bug fixes (backwards compatible)

### Release Checklist

Before releasing:

1. **Update version numbers**
2. **Test on multiple devices**
3. **Update CHANGELOG.md**
4. **Tag the release**
5. **Update documentation**

## 📜 Code of Conduct

### Our Standards

- **Be respectful** and inclusive
- **Welcome newcomers** and help them learn
- **Focus on constructive feedback**
- **Accept criticism gracefully**
- **Show empathy** towards other contributors

### Unacceptable Behavior

- Harassment or discrimination
- Personal attacks or insults
- Trolling or inflammatory comments
- Publishing private information
- Any conduct harmful to the community

### Enforcement

Violations may result in:

- Warning
- Temporary ban
- Permanent ban

Report issues to project maintainers.

---

Thank you for contributing to Android-Use! Your efforts help make AI-powered Android automation accessible to everyone.
