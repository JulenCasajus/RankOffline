#!/usr/bin/env ruby
# frozen_string_literal: true

# One-time, deterministic importer for the v0.2.1 translations. It retains only
# resources referenced by the v0.2.2 Kotlin engine, so legacy media/provider
# vocabulary cannot leak into the new APK through unused resource entries.

require "fileutils"
require "rexml/document"
require "set"

abort "usage: #{$PROGRAM_NAME} OLD_RES NEW_RES KOTLIN_ROOT" unless ARGV.length == 3

old_res, new_res, kotlin_root = ARGV.map { |path| File.expand_path(path) }
references = Set.new
Dir.glob(File.join(kotlin_root, "**", "*.kt")).sort.each do |path|
  File.read(path, encoding: "UTF-8").scan(/R\.(string|plurals)\.([A-Za-z0-9_]+)/) do |type, name|
    references << [type, name]
  end
end

Dir.glob(File.join(old_res, "values*", "strings.xml")).sort.each do |source|
  source_document = REXML::Document.new(File.read(source, encoding: "UTF-8"))
  output = REXML::Document.new
  output << REXML::XMLDecl.new("1.0", "utf-8")
  resources = output.add_element("resources")
  source_document.root&.elements&.each do |element|
    type = element.name == "string" ? "string" : element.name
    resources.add_element(element.deep_clone) if references.include?([type, element.attributes["name"]])
  end

  qualifier = File.basename(File.dirname(source))
  destination_dir = File.join(new_res, qualifier)
  FileUtils.mkdir_p(destination_dir)
  formatter = REXML::Formatters::Pretty.new(2)
  formatter.compact = true
  File.open(File.join(destination_dir, "strings.xml"), "w:UTF-8") do |file|
    formatter.write(output, file)
    file << "\n"
  end
end
