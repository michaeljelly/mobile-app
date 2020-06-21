import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class TestPage extends StatelessWidget {
  static final MethodChannel platform =
      MethodChannel('io.rebble.fossil/protocol');

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text("Fossil"),
      ),
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: <Widget>[
          RaisedButton(
            child: Text("Connect"),
            onPressed: () {
              //TODO
            },
          )
        ],
      ),
    );
  }
}
